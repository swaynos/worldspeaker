import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { decodeModifiedUtf8, parseClassFile, readZipEntries, verifyJar } from './verify-server-only.mjs';
import { recordFailureEvidence, runBoundedSubprocess } from './test-subprocess.mjs';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const verifier = join(repository, 'tools/compat/verify-server-only.mjs');
const deployment = JSON.parse(readFileSync(join(repository, 'compatibility/deployment.json'), 'utf8'));
const artifact = join(repository, `build/libs/${deployment.mod.id}-${deployment.mod.version}.jar`);

test('strict modified UTF-8 decoder preserves non-ASCII, NUL, and astral constants', () => {
  const bytes = compileClass({
    'org/worldspeaker/probe/Utf8Probe.java': 'package org.worldspeaker.probe; public final class Utf8Probe { public static final String VALUE = "\\u0000é😀"; }',
  }, 'org/worldspeaker/probe/Utf8Probe.class');
  const value = '\0é😀';
  const decodedConstants = classUtf8Constants(bytes).map(({ bytes: raw, index }) =>
    decodeModifiedUtf8(raw, 'Utf8Probe.class', index));

  assert.equal(decodedConstants.filter((constant) => constant === value).length, 1);
  assert.ok(parseClassFile(bytes, 'Utf8Probe.class').classes.has('org/worldspeaker/probe/Utf8Probe'));
});

test('strict modified UTF-8 decoder rejects malformed and forbidden byte sequences deterministically', () => {
  const invalid = [
    { bytes: [0x00], detail: 'forbidden raw NUL at byte=0' },
    { bytes: [0xc2], detail: 'truncated sequence at byte=0' },
    { bytes: [0xc2, 0x41], detail: 'bad continuation at byte=1' },
    { bytes: [0xe2, 0x82], detail: 'truncated sequence at byte=0' },
    { bytes: [0xe2, 0x28, 0xa1], detail: 'bad continuation at byte=1' },
    { bytes: [0xc0, 0x81], detail: 'disallowed overlong sequence at byte=0' },
    { bytes: [0xc1, 0x81], detail: 'disallowed overlong sequence at byte=0' },
    { bytes: [0xe0, 0x80, 0x80], detail: 'disallowed overlong sequence at byte=0' },
    { bytes: [0xf0, 0x9f, 0x98, 0x80], detail: 'forbidden four-byte sequence at byte=0' },
    { bytes: [0x80], detail: 'invalid lead byte=0x80 at byte=0' },
  ];
  for (const { bytes, detail } of invalid) {
    assert.throws(() => decodeModifiedUtf8(Buffer.from(bytes), 'Probe.class', 7), (error) => {
      assert.equal(error.code, 'malformed-class');
      assert.equal(error.message, `WS-SERVER-ONLY-REJECTED: malformed-class: entry=Probe.class; CONSTANT_Utf8 index=7; modified UTF-8 ${detail}`);
      return true;
    });
  }
});

test('compiled malformed modified UTF-8 constant is rejected by the CLI in a copy of the remapped JAR', () => {
  assert.ok(existsSync(artifact), 'run ./gradlew -PlocalDevelopment=true verifyServerOnly before this regression');
  const probe = compileClass({
    'org/worldspeaker/probe/Probe.java': 'package org.worldspeaker.probe; public final class Probe { public static final String VALUE = "SAFE"; }',
  }, 'org/worldspeaker/probe/Probe.class');
  const safe = classUtf8Constants(probe).find(({ bytes }) => bytes.equals(Buffer.from('SAFE', 'ascii')));
  assert.ok(safe, 'javac output should contain the compiled SAFE string constant');
  const mutatedProbe = Buffer.from(probe);
  mutatedProbe[safe.offset] = 0xff;

  const entries = validEntries();
  entries.set('org/worldspeaker/probe/Probe.class', mutatedProbe);
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-modified-utf8-'));
  try {
    const jar = join(fixture, 'mutated.jar');
    writeFileSync(jar, writeStoredZip(entries));
    const result = spawnSync(process.execPath, [verifier, '--jar', jar], {
      cwd: repository, encoding: 'utf8', timeout: 20_000, maxBuffer: 2 * 1024 * 1024,
    });
    const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
    assert.ifError(result.error);
    assert.equal(result.status, 1, `malformed class unexpectedly passed the CLI:\n${output}`);
    assert.match(output, /WS-SERVER-ONLY-REJECTED: malformed-class: entry=org\/worldspeaker\/probe\/Probe\.class; CONSTANT_Utf8 index=\d+; modified UTF-8 invalid lead byte=0xff at byte=0/);
    assert.doesNotMatch(output, /WS-SERVER-ONLY-PASS/);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('compiled remapped JAR passes bounded ZIP, metadata, and mapped-symbol inspection', () => {
  assert.ok(existsSync(artifact), 'run ./gradlew -PlocalDevelopment=true verifyServerOnly before this suite');
  const result = verifyJar(artifact);
  assert.equal(result.sha256, createHash('sha256').update(readFileSync(artifact)).digest('hex'));
  assert.ok(result.classes > 0);
  const entries = readZipEntries(readFileSync(artifact));
  assert.equal([...entries.keys()].some((name) => name.includes('WorldspeakerTickLoadHarness')), false,
    'the optional load harness must remain outside the production JAR');
});

test('command receipt handlers capture enclosing packets with void ModifyArgs signatures', () => {
  const entries = validEntries();
  const entry = 'org/worldspeaker/mixin/ServerPlayNetworkHandlerInvocationMixin.class';
  const bytes = entries.get(entry);
  const parsed = parseClassFile(bytes, entry);
  assert.ok(parsed.constants.includes('Lorg/spongepowered/asm/mixin/injection/ModifyArgs;'));
  assert.equal(parsed.constants.includes('Lorg/spongepowered/asm/mixin/injection/ModifyArg;'), false);
  assert.doesNotThrow(() => verifyFixtureEntries(entries));

  for (const name of ['worldspeaker$bindUnsignedCommandReceipt', 'worldspeaker$bindSignedCommandReceipt']) {
    const method = parsed.methods.find((candidate) => candidate.name === name);
    assert.ok(method, `missing ${name}`);
    assert.equal(method.access, 0x0002);
    assert.match(method.descriptor, /^\(Lorg\/spongepowered\/asm\/mixin\/injection\/invoke\/arg\/Args;Lnet\/minecraft\/class_\d+;\)V$/);
    const brokenDescriptor = method.descriptor.replace(
      'Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;', 'Ljava/lang/Runnable;'
    ).replace(/V$/, 'Ljava/lang/Runnable;');
    const constant = classUtf8Constants(bytes).find(({ bytes: value }) => value.toString('utf8') === method.descriptor);
    assert.ok(constant, 'handler descriptor must be present in the constant pool');
    const replacement = Buffer.from(brokenDescriptor, 'utf8');
    const length = Buffer.alloc(2);
    length.writeUInt16BE(replacement.length);
    const mutated = new Map(entries);
    mutated.set(entry, Buffer.concat([
      bytes.subarray(0, constant.offset - 2), length, replacement,
      bytes.subarray(constant.offset + constant.bytes.length),
    ]));
    assertRejected(mutated, 'command-capture-mixin-bytecode-invalid', name);
  }
});

test('production JAR pins the bounded private-file helper bytes', () => {
  const entries = validEntries();
  const helper = entries.get('org/worldspeaker/compat/private-file-reader.mjs');
  assert.ok(helper && helper.length > 0 && helper.length <= 16_384);
  assert.equal(createHash('sha256').update(helper).digest('hex'),
    'd7f34ba48fca521fc79fcce880d7c828efb2ac37c680dbfda892e53664818405');

  const mutated = new Map(entries);
  const changed = Buffer.from(helper);
  changed[0] ^= 1;
  mutated.set('org/worldspeaker/compat/private-file-reader.mjs', changed);
  assertRejected(mutated, 'private-reader-resource-invalid', 'org/worldspeaker/compat/private-file-reader.mjs');
});

test('packaged mixin allowlist is limited to reviewed server packet-capture and CommandOutput seams', () => {
  const entries = validEntries();
  const fabric = JSON.parse(entries.get('fabric.mod.json').toString('utf8'));
  const mixin = JSON.parse(entries.get('worldspeaker.mixins.json').toString('utf8'));
  assert.deepEqual(fabric.mixins, ['worldspeaker.mixins.json']);
  assert.deepEqual(mixin, {
    required: true,
    minVersion: '0.8.5',
    package: 'org.worldspeaker.mixin',
    compatibilityLevel: 'JAVA_21',
    refmap: 'worldspeaker-refmap.json',
    mixins: [
      'ServerCommandSourceOutputAccessor',
      'ChatMessageC2SPacketCaptureMixin',
      'CommandExecutionC2SPacketCaptureMixin',
      'ChatCommandSignedC2SPacketCaptureMixin',
      'SignedMessageCaptureMixin',
      'ServerPlayNetworkHandlerInvocationMixin',
    ],
  });
  assert.ok(entries.has('org/worldspeaker/mixin/ServerCommandSourceOutputAccessor.class'));
  assert.doesNotThrow(() => verifyFixtureEntries(entries));

  const extraConfig = new Map(entries);
  fabric.mixins = ['worldspeaker.mixins.json', 'unreviewed.mixins.json'];
  extraConfig.set('fabric.mod.json', jsonBytes(fabric));
  assertRejected(extraConfig, 'metadata-mixin-boundary-invalid');

  const broadenedConfig = new Map(entries);
  mixin.client = ['ClientSideMixin'];
  broadenedConfig.set('worldspeaker.mixins.json', jsonBytes(mixin));
  assertRejected(broadenedConfig, 'metadata-mixin-boundary-invalid');

  const alteredRefmap = new Map(entries);
  const refmap = JSON.parse(alteredRefmap.get('worldspeaker-refmap.json').toString('utf8'));
  refmap.mappings['org/worldspeaker/mixin/ServerCommandSourceOutputAccessor'].output = 'inventedField';
  alteredRefmap.set('worldspeaker-refmap.json', jsonBytes(refmap));
  assertRejected(alteredRefmap, 'metadata-mixin-boundary-invalid');

  const missingAccessor = new Map(entries);
  missingAccessor.delete('org/worldspeaker/mixin/ServerCommandSourceOutputAccessor.class');
  assertRejected(missingAccessor, 'mixin-class-boundary-invalid');

  const misplacedAccessor = new Map(entries);
  misplacedAccessor.set('org/worldspeaker/probe/UnreviewedMixin.class',
    entries.get('org/worldspeaker/mixin/ServerCommandSourceOutputAccessor.class'));
  assertRejected(misplacedAccessor, 'mixin-api-reference', 'org/worldspeaker/probe/UnreviewedMixin.class');
});

test('only the reviewed Fabric END_SERVER_TICK registration is allowed; unbounded server.execute stays rejected', () => {
  const hook = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> { void register(T listener); }',
    'net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents.java': 'package net.fabricmc.fabric.api.event.lifecycle.v1; public final class ServerTickEvents { public interface EndTick {} public static final net.fabricmc.fabric.api.event.Event<EndTick> END_SERVER_TICK = null; }',
    'org/worldspeaker/probe/TickHookProbe.java': 'package org.worldspeaker.probe; public final class TickHookProbe { public void register() { net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(null); } }',
  }, 'org/worldspeaker/probe/TickHookProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/TickHookProbe.class', hook);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));

  const execute = compileClass({
    'net/minecraft/server/MinecraftServer.java': 'package net.minecraft.server; public class MinecraftServer { public void execute(Runnable task) {} }',
    'org/worldspeaker/probe/ExecuteProbe.java': 'package org.worldspeaker.probe; public final class ExecuteProbe { public void post(net.minecraft.server.MinecraftServer server) { server.execute(() -> {}); } }',
  }, 'org/worldspeaker/probe/ExecuteProbe.class');
  const rejected = validEntries();
  rejected.set('org/worldspeaker/probe/ExecuteProbe.class', execute);
  assertRejected(rejected, 'unknown-game-api-reference', 'MinecraftServer#execute');
});

test('only reviewed inherited ServerPlayerEntity liveness and block-position accessors are allowed', () => {
  const probe = compileClass({
    'net/minecraft/class_3222.java': 'package net.minecraft; public class class_3222 { public boolean method_5805() { return true; } public int method_31477() { return 0; } public int method_31478() { return 0; } public int method_31479() { return 0; } }',
    'org/worldspeaker/probe/PlayerObservationProbe.java': 'package org.worldspeaker.probe; public final class PlayerObservationProbe { public int observe(net.minecraft.class_3222 player) { return (player.method_5805() ? 1 : 0) + player.method_31477() + player.method_31478() + player.method_31479(); } }',
  }, 'org/worldspeaker/probe/PlayerObservationProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/PlayerObservationProbe.class', probe);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));
});

test('only reviewed intermediary player-death callback types map to LivingEntity and DamageSource', () => {
  const probe = compileClass({
    'net/minecraft/class_1309.java': 'package net.minecraft; public class class_1309 {}',
    'net/minecraft/class_1282.java': 'package net.minecraft; public class class_1282 {}',
    'org/worldspeaker/probe/PlayerDeathTypesProbe.java': 'package org.worldspeaker.probe; public interface PlayerDeathTypesProbe { void afterDeath(net.minecraft.class_1309 entity, net.minecraft.class_1282 source); }',
  }, 'org/worldspeaker/probe/PlayerDeathTypesProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/PlayerDeathTypesProbe.class', probe);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));
});

test('only the reviewed inherited ServerWorld registry-key accessor is allowed', () => {
  const probe = compileClass({
    'net/minecraft/class_5321.java': 'package net.minecraft; public final class class_5321 {}',
    'net/minecraft/class_3218.java': 'package net.minecraft; public class class_3218 { public class_5321 method_27983() { return null; } }',
    'org/worldspeaker/probe/ServerWorldKeyProbe.java': 'package org.worldspeaker.probe; public final class ServerWorldKeyProbe { public Object key(net.minecraft.class_3218 world) { return world.method_27983(); } }',
  }, 'org/worldspeaker/probe/ServerWorldKeyProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/ServerWorldKeyProbe.class', probe);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));
});

test('only the exact Fabric player dimension-change and respawn invalidation hooks are allowed', () => {
  const lifecycle = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> { void register(T listener); }',
    'net/minecraft/server/network/ServerPlayerEntity.java': 'package net.minecraft.server.network; public class ServerPlayerEntity {}',
    'net/minecraft/server/world/ServerWorld.java': 'package net.minecraft.server.world; public class ServerWorld {}',
    'net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents.java': 'package net.fabricmc.fabric.api.entity.event.v1; public final class ServerEntityWorldChangeEvents { public interface AfterPlayerChange { void afterChangeWorld(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.server.world.ServerWorld origin, net.minecraft.server.world.ServerWorld destination); } public static final net.fabricmc.fabric.api.event.Event<AfterPlayerChange> AFTER_PLAYER_CHANGE_WORLD = null; public static final net.fabricmc.fabric.api.event.Event<AfterPlayerChange> BEFORE_PLAYER_CHANGE_WORLD = null; }',
    'net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents.java': 'package net.fabricmc.fabric.api.entity.event.v1; public final class ServerPlayerEvents { public interface AfterRespawn { void afterRespawn(net.minecraft.server.network.ServerPlayerEntity oldPlayer, net.minecraft.server.network.ServerPlayerEntity newPlayer, boolean alive); } public static final net.fabricmc.fabric.api.event.Event<AfterRespawn> AFTER_RESPAWN = null; }',
    'org/worldspeaker/probe/PlayerLifecycleProbe.java': 'package org.worldspeaker.probe; public final class PlayerLifecycleProbe { public void register() { net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> close(player)); net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> close(newPlayer)); } private void close(net.minecraft.server.network.ServerPlayerEntity player) {} }',
  }, 'org/worldspeaker/probe/PlayerLifecycleProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/PlayerLifecycleProbe.class', lifecycle);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));

  const unreviewed = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> {}',
    'net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents.java': 'package net.fabricmc.fabric.api.entity.event.v1; public final class ServerEntityWorldChangeEvents { public static final net.fabricmc.fabric.api.event.Event<Object> BEFORE_PLAYER_CHANGE_WORLD = null; }',
    'org/worldspeaker/probe/UnreviewedPlayerEventProbe.java': 'package org.worldspeaker.probe; public final class UnreviewedPlayerEventProbe { public Object event() { return net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.BEFORE_PLAYER_CHANGE_WORLD; } }',
  }, 'org/worldspeaker/probe/UnreviewedPlayerEventProbe.class');
  const rejected = validEntries();
  rejected.set('org/worldspeaker/probe/UnreviewedPlayerEventProbe.class', unreviewed);
  assertRejected(rejected, 'unknown-game-api-reference', 'BEFORE_PLAYER_CHANGE_WORLD');
});

test('only reviewed connection lifecycle and observational death callbacks are allowed', () => {
  const lifecycle = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> { void register(T listener); }',
    'net/fabricmc/fabric/api/networking/v1/PacketSender.java': 'package net.fabricmc.fabric.api.networking.v1; public interface PacketSender {}',
    'net/minecraft/server/MinecraftServer.java': 'package net.minecraft.server; public class MinecraftServer {}',
    'net/minecraft/server/network/ServerPlayNetworkHandler.java': 'package net.minecraft.server.network; public class ServerPlayNetworkHandler {}',
    'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.java': 'package net.fabricmc.fabric.api.networking.v1; public final class ServerPlayConnectionEvents { public interface Join { void onPlayReady(net.minecraft.server.network.ServerPlayNetworkHandler handler, PacketSender sender, net.minecraft.server.MinecraftServer server); } public static final net.fabricmc.fabric.api.event.Event<Join> JOIN = null; }',
    'net/minecraft/entity/LivingEntity.java': 'package net.minecraft.entity; public class LivingEntity {}',
    'net/minecraft/entity/damage/DamageSource.java': 'package net.minecraft.entity.damage; public class DamageSource {}',
    'net/minecraft/server/network/ServerPlayerEntity.java': 'package net.minecraft.server.network; public class ServerPlayerEntity extends net.minecraft.entity.LivingEntity {}',
    'net/fabricmc/fabric/api/entity/event/v1/ServerLivingEntityEvents.java': 'package net.fabricmc.fabric.api.entity.event.v1; public final class ServerLivingEntityEvents { public interface AfterDeath { void afterDeath(net.minecraft.entity.LivingEntity entity, net.minecraft.entity.damage.DamageSource source); } public static final net.fabricmc.fabric.api.event.Event<AfterDeath> AFTER_DEATH = null; public static final net.fabricmc.fabric.api.event.Event<Object> ALLOW_DEATH = null; }',
    'org/worldspeaker/probe/ReviewedAttentionLifecycleProbe.java': 'package org.worldspeaker.probe; public final class ReviewedAttentionLifecycleProbe { public void register() { net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joined(handler)); net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> { if (entity instanceof net.minecraft.server.network.ServerPlayerEntity player) died(player); }); } private void joined(net.minecraft.server.network.ServerPlayNetworkHandler handler) {} private void died(net.minecraft.server.network.ServerPlayerEntity player) {} }',
  }, 'org/worldspeaker/probe/ReviewedAttentionLifecycleProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/ReviewedAttentionLifecycleProbe.class', lifecycle);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));

  const cancelDeath = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> {}',
    'net/fabricmc/fabric/api/entity/event/v1/ServerLivingEntityEvents.java': 'package net.fabricmc.fabric.api.entity.event.v1; public final class ServerLivingEntityEvents { public static final net.fabricmc.fabric.api.event.Event<Object> ALLOW_DEATH = null; }',
    'org/worldspeaker/probe/CancelDeathProbe.java': 'package org.worldspeaker.probe; public final class CancelDeathProbe { public Object event() { return net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH; } }',
  }, 'org/worldspeaker/probe/CancelDeathProbe.class');
  const rejected = validEntries();
  rejected.set('org/worldspeaker/probe/CancelDeathProbe.class', cancelDeath);
  assertRejected(rejected, 'unknown-game-api-reference', 'ALLOW_DEATH');
});

test('only the pinned Fabric CHAT_MESSAGE observer and exact read-only signed envelope APIs are allowed', () => {
  const observer = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> { void register(T listener); }',
    'net/fabricmc/fabric/api/message/v1/ServerMessageEvents.java': 'package net.fabricmc.fabric.api.message.v1; public final class ServerMessageEvents { public static final net.fabricmc.fabric.api.event.Event<ChatMessage> CHAT_MESSAGE = null; public static final net.fabricmc.fabric.api.event.Event<ChatMessage> ALLOW_CHAT_MESSAGE = null; public interface ChatMessage { void onChatMessage(net.minecraft.class_7471 message, net.minecraft.class_3222 sender, net.minecraft.class_2556.class_7602 params); } }',
    'net/minecraft/class_2556.java': 'package net.minecraft; public final class class_2556 { public static final class_5321 field_11737 = null; public static class class_7602 { public class_6880<?> comp_919() { return null; } } }',
    'net/minecraft/class_5321.java': 'package net.minecraft; public final class class_5321 {}',
    'net/minecraft/class_6880.java': 'package net.minecraft; public final class class_6880<T> { public boolean method_40225(class_5321 key) { return false; } public boolean matchesKey(class_5321 key) { return false; } }',
    'net/minecraft/class_7649.java': 'package net.minecraft; public final class class_7649 { public boolean method_45087() { return true; } }',
    'net/minecraft/class_7471.java': 'package net.minecraft; public final class class_7471 { public boolean method_46294() { return true; } public java.util.UUID method_46292() { return null; } public String method_44862() { return ""; } public class_7649 comp_981() { return null; } }',
    'net/minecraft/class_3222.java': 'package net.minecraft; public final class class_3222 {}',
    'org/worldspeaker/probe/ReviewedChatObserverProbe.java': 'package org.worldspeaker.probe; public final class ReviewedChatObserverProbe { public void register() { net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> { if (params.comp_919().method_40225(net.minecraft.class_2556.field_11737) && message.method_46294() && message.method_46292() != null && message.comp_981().method_45087()) consume(message.method_44862()); }); } private void consume(String content) {} }',
  }, 'org/worldspeaker/probe/ReviewedChatObserverProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/ReviewedChatObserverProbe.class', observer);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));

  const unreviewedEvent = compileClass({
    'net/fabricmc/fabric/api/event/Event.java': 'package net.fabricmc.fabric.api.event; public interface Event<T> { void register(T listener); }',
    'net/fabricmc/fabric/api/message/v1/ServerMessageEvents.java': 'package net.fabricmc.fabric.api.message.v1; public final class ServerMessageEvents { public static final net.fabricmc.fabric.api.event.Event<ChatMessage> CHAT_MESSAGE = null; public static final net.fabricmc.fabric.api.event.Event<ChatMessage> ALLOW_CHAT_MESSAGE = null; public interface ChatMessage {} }',
    'org/worldspeaker/probe/UnreviewedChatEventProbe.java': 'package org.worldspeaker.probe; public final class UnreviewedChatEventProbe { public Object register() { return net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE; } }',
  }, 'org/worldspeaker/probe/UnreviewedChatEventProbe.class');
  const rejectedEvent = validEntries();
  rejectedEvent.set('org/worldspeaker/probe/UnreviewedChatEventProbe.class', unreviewedEvent);
  assertRejected(rejectedEvent, 'unknown-game-api-reference', 'ALLOW_CHAT_MESSAGE');

  const decorated = compileClass({
    'net/minecraft/network/message/SignedMessage.java': 'package net.minecraft.network.message; public final class SignedMessage { public String getContent() { return ""; } }',
    'org/worldspeaker/probe/DecoratedContentProbe.java': 'package org.worldspeaker.probe; public final class DecoratedContentProbe { public String read(net.minecraft.network.message.SignedMessage message) { return message.getContent(); } }',
  }, 'org/worldspeaker/probe/DecoratedContentProbe.class');
  const rejectedContent = validEntries();
  rejectedContent.set('org/worldspeaker/probe/DecoratedContentProbe.class', decorated);
  assertRejected(rejectedContent, 'signed-player-chat-api-reference', 'SignedMessage#getContent');
});

test('only the pinned authenticated-source and generic Brigadier registration APIs are accepted', () => {
  const sourceProbe = compileClass({
    'net/minecraft/entity/Entity.java': 'package net.minecraft.entity; public class Entity {}',
    'net/minecraft/world/World.java': 'package net.minecraft.world; public class World {}',
    'net/minecraft/class_1297.java': 'package net.minecraft; public class class_1297 extends net.minecraft.entity.Entity { public net.minecraft.server.MinecraftServer method_5682() { return null; } }',
    'net/minecraft/class_3218.java': 'package net.minecraft; public class class_3218 extends net.minecraft.world.World {}',
    'net/minecraft/server/MinecraftServer.java': 'package net.minecraft.server; public class MinecraftServer { public boolean method_3828() { return true; } public net.minecraft.class_3324 method_3760() { return null; } }',
    'net/minecraft/class_3324.java': 'package net.minecraft; public class class_3324 { public class_3222 method_14602(java.util.UUID id) { return null; } }',
    'net/minecraft/class_3244.java': 'package net.minecraft; public class class_3244 { public class_3222 field_14140; public boolean method_48106() { return true; } }',
    'net/minecraft/class_3222.java': 'package net.minecraft; public class class_3222 extends class_1297 { public class_3244 field_13987; public net.minecraft.server.MinecraftServer method_5682() { return null; } public java.util.UUID getUuid() { return null; } public net.minecraft.world.World getWorld() { return null; } public com.mojang.authlib.GameProfile getGameProfile() { return null; } }',
    'net/minecraft/class_2168.java': 'package net.minecraft; public class class_2168 { public class_3222 method_44023() { return null; } public net.minecraft.server.MinecraftServer method_9211() { return null; } public class_1297 method_9228() { return null; } public class_3218 method_9225() { return null; } public void method_9213(class_2561 text) {} }',
    'net/minecraft/class_2561.java': 'package net.minecraft; public interface class_2561 {}',
    'com/mojang/authlib/GameProfile.java': 'package com.mojang.authlib; public class GameProfile { public String getName() { return ""; } }',
    'org/worldspeaker/probe/ReviewedSourceProbe.java': 'package org.worldspeaker.probe; public final class ReviewedSourceProbe { public boolean check(net.minecraft.class_2168 source) { net.minecraft.class_3222 player = source.method_44023(); net.minecraft.server.MinecraftServer server = source.method_9211(); return server.method_3828() && server.method_3760().method_14602(player.getUuid()) == player && player.method_5682() == server && source.method_9228() == player && source.method_9225() == player.getWorld() && player.field_13987.field_14140 == player && player.field_13987.method_48106() && player.getGameProfile().getName().length() >= 0; } public void refuse(net.minecraft.class_2168 source, net.minecraft.class_2561 error) { source.method_9213(error); } }',
  }, 'org/worldspeaker/probe/ReviewedSourceProbe.class');
  const brigadierProbe = compileClass({
    'com/mojang/brigadier/Command.java': 'package com.mojang.brigadier; public interface Command<S> { int run(com.mojang.brigadier.context.CommandContext<S> context); }',
    'com/mojang/brigadier/CommandDispatcher.java': 'package com.mojang.brigadier; public class CommandDispatcher<S> { public com.mojang.brigadier.tree.LiteralCommandNode<S> register(com.mojang.brigadier.builder.LiteralArgumentBuilder<S> builder) { return null; } }',
    'com/mojang/brigadier/arguments/ArgumentType.java': 'package com.mojang.brigadier.arguments; public interface ArgumentType<T> {}',
    'com/mojang/brigadier/arguments/StringArgumentType.java': 'package com.mojang.brigadier.arguments; public class StringArgumentType implements ArgumentType<String> { public static StringArgumentType greedyString() { return null; } public static String getString(com.mojang.brigadier.context.CommandContext<?> context, String name) { return ""; } }',
    'com/mojang/brigadier/builder/ArgumentBuilder.java': 'package com.mojang.brigadier.builder; public abstract class ArgumentBuilder<S, T extends ArgumentBuilder<S,T>> { @SuppressWarnings("unchecked") public T executes(com.mojang.brigadier.Command<S> command) { return (T)this; } @SuppressWarnings("unchecked") public T then(ArgumentBuilder<S,?> child) { return (T)this; } }',
    'com/mojang/brigadier/builder/LiteralArgumentBuilder.java': 'package com.mojang.brigadier.builder; public class LiteralArgumentBuilder<S> extends ArgumentBuilder<S,LiteralArgumentBuilder<S>> { public static <S> LiteralArgumentBuilder<S> literal(String name) { return null; } }',
    'com/mojang/brigadier/builder/RequiredArgumentBuilder.java': 'package com.mojang.brigadier.builder; public class RequiredArgumentBuilder<S,T> extends ArgumentBuilder<S,RequiredArgumentBuilder<S,T>> { public static <S,T> RequiredArgumentBuilder<S,T> argument(String name, com.mojang.brigadier.arguments.ArgumentType<T> type) { return null; } }',
    'com/mojang/brigadier/context/CommandContext.java': 'package com.mojang.brigadier.context; public class CommandContext<S> { public String getInput() { return ""; } public S getSource() { return null; } }',
    'com/mojang/brigadier/tree/LiteralCommandNode.java': 'package com.mojang.brigadier.tree; public class LiteralCommandNode<S> {}',
    'org/worldspeaker/probe/ReviewedBrigadierProbe.java': 'package org.worldspeaker.probe; public final class ReviewedBrigadierProbe { public Object register(com.mojang.brigadier.CommandDispatcher<Object> dispatcher) { return dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.<Object>literal("worldspeaker").executes(context -> context.getInput().length()).then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<Object>literal("ask").then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<Object,String>argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(context -> com.mojang.brigadier.arguments.StringArgumentType.getString(context, "text").length())))); } }',
  }, 'org/worldspeaker/probe/ReviewedBrigadierProbe.class');
  const accepted = validEntries();
  accepted.set('org/worldspeaker/probe/ReviewedSourceProbe.class', sourceProbe);
  accepted.set('org/worldspeaker/probe/ReviewedBrigadierProbe.class', brigadierProbe);
  assert.doesNotThrow(() => verifyFixtureEntries(accepted));

  const unreviewed = compileClass({
    'net/minecraft/class_2168.java': 'package net.minecraft; public class class_2168 { public int method_99999() { return 4; } }',
    'org/worldspeaker/probe/UnreviewedSourceProbe.java': 'package org.worldspeaker.probe; public final class UnreviewedSourceProbe { public int privilege(net.minecraft.class_2168 source) { return source.method_99999(); } }',
  }, 'org/worldspeaker/probe/UnreviewedSourceProbe.class');
  const rejected = validEntries();
  rejected.set('org/worldspeaker/probe/UnreviewedSourceProbe.class', unreviewed);
  assertRejected(rejected, 'unknown-game-api-reference', 'method_99999');
});

test('client entrypoint and client package bytecode are rejected', () => {
  const metadata = validEntries();
  const fabric = JSON.parse(metadata.get('fabric.mod.json').toString('utf8'));
  fabric.entrypoints.client = ['org.worldspeaker.ClientInitializer'];
  metadata.set('fabric.mod.json', jsonBytes(fabric));
  assertRejected(metadata, 'metadata-entrypoint-drift');

  const clientClass = compileClass({
    'org/worldspeaker/client/ClientInitializer.java': 'package org.worldspeaker.client; public final class ClientInitializer {}',
  }, 'org/worldspeaker/client/ClientInitializer.class');
  const withClientBytecode = validEntries();
  withClientBytecode.set('org/worldspeaker/client/ClientInitializer.class', clientClass);
  assertRejected(withClientBytecode, 'client-class');
});

test('mapped custom-payload registration/send bytecode is rejected', () => {
  const payload = compileClass({
    'net/minecraft/server/network/ServerPlayerEntity.java': 'package net.minecraft.server.network; public class ServerPlayerEntity {}',
    'net/minecraft/network/packet/CustomPayload.java': 'package net.minecraft.network.packet; public interface CustomPayload {}',
    'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.java': 'package net.fabricmc.fabric.api.networking.v1; public final class ServerPlayNetworking { public static void send(net.minecraft.server.network.ServerPlayerEntity p, net.minecraft.network.packet.CustomPayload c) {} }',
    'org/worldspeaker/probe/PayloadProbe.java': 'package org.worldspeaker.probe; public final class PayloadProbe { public void send(net.minecraft.server.network.ServerPlayerEntity p, net.minecraft.network.packet.CustomPayload c) { net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, c); } }',
  }, 'org/worldspeaker/probe/PayloadProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/PayloadProbe.class', payload);
  assertRejected(entries, 'custom-payload-api-reference', 'ServerPlayNetworking#send');
});

test('mapped resource-pack request bytecode is rejected', () => {
  const pack = compileClass({
    'net/minecraft/server/network/ServerConfigurationNetworkHandler.java': 'package net.minecraft.server.network; public class ServerConfigurationNetworkHandler { public void queueSendResourcePackTask() {} }',
    'org/worldspeaker/probe/PackProbe.java': 'package org.worldspeaker.probe; public final class PackProbe { public void ask(net.minecraft.server.network.ServerConfigurationNetworkHandler handler) { handler.queueSendResourcePackTask(); } }',
  }, 'org/worldspeaker/probe/PackProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/PackProbe.class', pack);
  assertRejected(entries, 'resource-pack-api-reference', 'queueSendResourcePackTask');
});

test('signed-player-chat construction bytecode is rejected', () => {
  const signed = compileClass({
    'net/minecraft/network/message/SignedMessage.java': 'package net.minecraft.network.message; public class SignedMessage { public SignedMessage() {} }',
    'org/worldspeaker/probe/SignedProbe.java': 'package org.worldspeaker.probe; public final class SignedProbe { public Object forge() { return new net.minecraft.network.message.SignedMessage(); } }',
  }, 'org/worldspeaker/probe/SignedProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/SignedProbe.class', signed);
  assertRejected(entries, 'signed-player-chat-api-reference', 'SignedMessage#<init>');
});

test('project-local class names cannot be mistaken for signed player-chat API references', () => {
  const localClass = compileClass({
    'org/worldspeaker/probe/ChatCommandSignedC2SPacketCaptureMixinProbe.java':
      'package org.worldspeaker.probe; public final class ChatCommandSignedC2SPacketCaptureMixinProbe {}',
  }, 'org/worldspeaker/probe/ChatCommandSignedC2SPacketCaptureMixinProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/ChatCommandSignedC2SPacketCaptureMixinProbe.class', localClass);
  assert.doesNotThrow(() => verifyFixtureEntries(entries));
});

test('signed-message factories and signature mutation stay denied beside the read-only allowlist', () => {
  const operations = [
    { name: 'ofUnsigned', declaration: 'public static SignedMessage ofUnsigned(String content) { return null; }', call: 'net.minecraft.network.message.SignedMessage.ofUnsigned("text")' },
    { name: 'stripSignature', declaration: 'public SignedMessage stripSignature() { return this; }', call: 'message.stripSignature()' },
    { name: 'withUnsignedContent', declaration: 'public SignedMessage withUnsignedContent(String content) { return this; }', call: 'message.withUnsignedContent("text")' },
    { name: 'withFilterMask', declaration: 'public SignedMessage withFilterMask(Object mask) { return this; }', call: 'message.withFilterMask(null)' },
  ];
  for (const operation of operations) {
    const probe = compileClass({
      'net/minecraft/network/message/SignedMessage.java': `package net.minecraft.network.message; public final class SignedMessage { ${operation.declaration} }`,
      'org/worldspeaker/probe/SignedMutationProbe.java': `package org.worldspeaker.probe; public final class SignedMutationProbe { public Object mutate(net.minecraft.network.message.SignedMessage message) { return ${operation.call}; } }`,
    }, 'org/worldspeaker/probe/SignedMutationProbe.class');
    const entries = validEntries();
    entries.set('org/worldspeaker/probe/SignedMutationProbe.class', probe);
    assertRejected(entries, 'signed-player-chat-api-reference', `SignedMessage#${operation.name}`);
  }
});

test('custom registry registration bytecode is rejected', () => {
  const registry = compileClass({
    'net/minecraft/registry/Registry.java': 'package net.minecraft.registry; public class Registry<T> { public static Object register(Registry<?> r, Object id, Object value) { return value; } }',
    'org/worldspeaker/probe/RegistryProbe.java': 'package org.worldspeaker.probe; public final class RegistryProbe { public Object register(net.minecraft.registry.Registry<?> registry) { return net.minecraft.registry.Registry.register(registry, "id", new Object()); } }',
  }, 'org/worldspeaker/probe/RegistryProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/RegistryProbe.class', registry);
  assertRejected(entries, 'custom-registry-api-reference', 'Registry#register');
});

test('intermediary registry symbols are resolved to their pinned Yarn names before policy checks', () => {
  const registry = compileClass({
    'net/minecraft/class_2960.java': 'package net.minecraft; public final class class_2960 {}',
    'net/minecraft/class_2378.java': 'package net.minecraft; public final class class_2378 { public static Object method_10230(class_2378 registry, class_2960 id, Object value) { return value; } }',
    'org/worldspeaker/probe/IntermediaryRegistryProbe.java': 'package org.worldspeaker.probe; public final class IntermediaryRegistryProbe { public Object register(net.minecraft.class_2378 registry) { return net.minecraft.class_2378.method_10230(registry, null, new Object()); } }',
  }, 'org/worldspeaker/probe/IntermediaryRegistryProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/IntermediaryRegistryProbe.class', registry);
  assertRejected(entries, 'custom-registry-api-reference', 'net/minecraft/class_2378#method_10230');
  assertRejectedMappedSymbolForIntermediaryReference(entries);
});

test('unknown mapped server API references fail closed', () => {
  const unknown = compileClass({
    'net/minecraft/server/MinecraftServer.java': 'package net.minecraft.server; public class MinecraftServer { public void undocumentedGameOperation() {} }',
    'org/worldspeaker/probe/UnknownApiProbe.java': 'package org.worldspeaker.probe; public final class UnknownApiProbe { public void call(net.minecraft.server.MinecraftServer server) { server.undocumentedGameOperation(); } }',
  }, 'org/worldspeaker/probe/UnknownApiProbe.class');
  const entries = validEntries();
  entries.set('org/worldspeaker/probe/UnknownApiProbe.class', unknown);
  assertRejected(entries, 'unknown-game-api-reference', 'undocumentedGameOperation');
});

test('assets, nested JARs, malformed ZIP/class bytes, and metadata drift are refused', () => {
  const assets = validEntries();
  assets.set('assets/worldspeaker/lang/en_us.json', jsonBytes({}));
  assertRejected(assets, 'custom-content-entry', 'assets/worldspeaker/lang/en_us.json');

  const nested = validEntries();
  nested.set('META-INF/jars/nested.jar', Buffer.from('nested'));
  assertRejected(nested, 'nested-jar', 'META-INF/jars/nested.jar');

  const badClass = validEntries();
  badClass.set('org/worldspeaker/Broken.class', Buffer.from([0, 1, 2, 3]));
  assertRejected(badClass, 'malformed-class', 'Broken.class');

  const drift = validEntries();
  const fabric = JSON.parse(drift.get('fabric.mod.json').toString('utf8'));
  fabric.depends.minecraft = '1.20.1';
  drift.set('fabric.mod.json', jsonBytes(fabric));
  assertRejected(drift, 'metadata-dependency-drift', 'fabric.mod.json');

  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-zip-'));
  try {
    const malformed = join(fixture, 'malformed.jar');
    writeFileSync(malformed, Buffer.alloc(22));
    assert.throws(() => verifyJar(malformed), /WS-SERVER-ONLY-REJECTED: invalid-zip/);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('assembleCandidate rechecks a bad remapped JAR when verifyServerOnly is excluded and preserves output', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-candidate-boundary-'));
  try {
    copyGradleFixture(fixture);
    installSyntheticFactsInFixtureOnly(fixture);
    const candidate = validEntries();
    candidate.set('assets/worldspeaker/forbidden.json', jsonBytes({}));
    const fixtureJar = join(fixture, 'build/libs', `${deployment.mod.id}-${deployment.mod.version}.jar`);
    mkdirSync(dirname(fixtureJar), { recursive: true });
    writeFileSync(fixtureJar, writeStoredZip(candidate));
    writeFreshCompanionArchive(fixture);

    const output = join(fixture, 'build/distributions/worldspeaker-candidate');
    mkdirSync(output, { recursive: true });
    const marker = join(output, 'preexisting-output-marker.txt');
    writeFileSync(marker, 'preserve candidate output\n');
    const beforeNames = readdirSync(output).sort();

    const result = await runBoundedSubprocess(join(fixture, 'gradlew'), [
      '--offline', '--no-daemon', '--console=plain', '--max-workers=1', '-PlocalDevelopment=true', '-p', fixture,
      'assembleCandidate', '-x', 'verifyServerOnly', '-x', 'packageCompanion',
      '-x', 'validateDeploymentContract', '-x', 'validateApprovedQualification',
      '-x', 'verifyCandidateBuildEnvironment', '-x', 'build', '-x', 'generateCompatibilityDocs',
    ], {
      cwd: fixture,
      timeoutMs: 300_000,
      maxBuffer: 16 * 1024 * 1024,
      env: { ...process.env, GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? join(repository, '.compat-cache/gradle') },
      testLabel: 'server-only-candidate-boundary',
    });
    recordFailureEvidence(result);
    const outputText = privateOutputOf(result);
    const evidence = evidenceContext(result);
    assert.ifError(result.error);
    assert.notEqual(result.status, 0, `bad JAR unexpectedly crossed candidate copy boundary; ${evidence}`);
    assert.ok(/WS-SERVER-ONLY-REJECTED: custom-content-entry: entry=assets\/worldspeaker\/forbidden\.json/.test(outputText),
      `missing fixed server-only rejection marker; ${evidence}`);
    assert.ok(/> Task :assembleCandidate(?:\s|$)/.test(outputText), `missing assembleCandidate task marker; ${evidence}`);
    assert.ok(!/> Task :verifyServerOnly(?:\s|$)/.test(outputText),
      `the named verifier task was not excluded; ${evidence}`);
    assert.deepEqual(readdirSync(output).sort(), beforeNames);
    assert.equal(readFileSync(marker, 'utf8'), 'preserve candidate output\n');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('assembleRelease rechecks bytecode before output mutation when verifyReleaseCompatibility is excluded', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-release-boundary-'));
  try {
    copyGradleFixture(fixture);
    installSyntheticFactsInFixtureOnly(fixture);
    installNoopApprovalCheckInFixtureOnly(fixture);
    const releaseCandidate = validEntries();
    releaseCandidate.set('META-INF/jars/forbidden.jar', Buffer.from('nested'));
    const fixtureJar = join(fixture, 'build/libs', `${deployment.mod.id}-${deployment.mod.version}.jar`);
    mkdirSync(dirname(fixtureJar), { recursive: true });
    writeFileSync(fixtureJar, writeStoredZip(releaseCandidate));

    const output = join(fixture, 'build/distributions/worldspeaker-release');
    mkdirSync(output, { recursive: true });
    const marker = join(output, 'preexisting-output-marker.txt');
    writeFileSync(marker, 'preserve release output\n');
    const beforeNames = readdirSync(output).sort();

    const result = await runBoundedSubprocess(join(fixture, 'gradlew'), [
      '--offline', '--no-daemon', '--console=plain', '--max-workers=1', '-PlocalDevelopment=true', '-p', fixture,
      'assembleRelease', '-x', 'verifyReleaseCompatibility', '-x', 'validateDeploymentContract',
    ], {
      cwd: fixture,
      timeoutMs: 300_000,
      maxBuffer: 16 * 1024 * 1024,
      env: { ...process.env, GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? join(repository, '.compat-cache/gradle') },
      testLabel: 'server-only-release-boundary',
    });
    recordFailureEvidence(result);
    const outputText = privateOutputOf(result);
    const evidence = evidenceContext(result);
    assert.ifError(result.error);
    assert.notEqual(result.status, 0, `bad JAR unexpectedly crossed release copy boundary; ${evidence}`);
    assert.ok(/WS-SERVER-ONLY-REJECTED: nested-jar: entry=META-INF\/jars\/forbidden\.jar/.test(outputText),
      `missing fixed nested-JAR rejection marker; ${evidence}`);
    assert.ok(/> Task :assembleRelease(?:\s|$)/.test(outputText), `missing assembleRelease task marker; ${evidence}`);
    assert.ok(!/> Task :verifyReleaseCompatibility(?:\s|$)/.test(outputText),
      `the release verifier task was not excluded; ${evidence}`);
    assert.deepEqual(readdirSync(output).sort(), beforeNames);
    assert.equal(readFileSync(marker, 'utf8'), 'preserve release output\n');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

function privateOutputOf(result) {
  if (result.evidencePath) {
    return `${readFileSync(join(result.evidencePath, 'stdout.log'), 'utf8')}\n${readFileSync(join(result.evidencePath, 'stderr.log'), 'utf8')}`;
  }
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
}

function evidenceContext(result) {
  return result.evidencePath ? `private child evidence: ${result.evidencePath}` : `evidence unavailable (${result.evidenceCode ?? 'none'})`;
}

function validEntries() {
  return readZipEntries(readFileSync(artifact));
}

function assertRejected(entries, code, reference = '') {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-reject-'));
  try {
    const jar = join(fixture, 'mutated.jar');
    writeFileSync(jar, writeStoredZip(entries));
    assert.throws(() => verifyJar(jar), (error) => {
      assert.match(error.message, new RegExp(`WS-SERVER-ONLY-REJECTED: ${escapeRegex(code)}`));
      if (reference) assert.ok(error.message.includes(reference), error.message);
      return true;
    });
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
}

function verifyFixtureEntries(entries) {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-accept-'));
  try {
    const jar = join(fixture, 'fixture.jar');
    writeFileSync(jar, writeStoredZip(entries));
    return verifyJar(jar);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
}

function assertRejectedMappedSymbolForIntermediaryReference(entries) {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-mapped-'));
  try {
    const jar = join(fixture, 'mutated.jar');
    writeFileSync(jar, writeStoredZip(entries));
    assert.throws(() => verifyJar(jar), (error) => {
      assert.ok(error.message.includes('mapped=net/minecraft/registry/Registry#register('), error.message);
      return true;
    });
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
}

function compileClass(sources, classFile) {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-server-only-compile-'));
  try {
    const sourcePaths = [];
    for (const [name, source] of Object.entries(sources)) {
      const path = join(fixture, 'src', name);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, source);
      sourcePaths.push(path);
    }
    const classes = join(fixture, 'classes');
    mkdirSync(classes, { recursive: true });
    const compiled = spawnSync('javac', ['--release', '21', '-d', classes, ...sourcePaths], {
      encoding: 'utf8', timeout: 15_000, maxBuffer: 2 * 1024 * 1024,
    });
    assert.ifError(compiled.error);
    assert.equal(compiled.status, 0, compiled.stderr);
    return readFileSync(join(classes, classFile));
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
}

function classUtf8Constants(bytes) {
  assert.equal(bytes.readUInt32BE(0), 0xcafebabe);
  const count = bytes.readUInt16BE(8);
  const constants = [];
  let offset = 10;
  const skip = (length) => {
    assert.ok(offset + length <= bytes.length, 'compiled class constant pool must be in bounds');
    offset += length;
  };
  for (let index = 1; index < count; index += 1) {
    const tag = bytes[offset++];
    switch (tag) {
      case 1: {
        const length = bytes.readUInt16BE(offset);
        offset += 2;
        assert.ok(offset + length <= bytes.length, 'compiled UTF-8 constant must be in bounds');
        constants.push({ index, offset, bytes: bytes.subarray(offset, offset + length) });
        skip(length);
        break;
      }
      case 3:
      case 4:
        skip(4);
        break;
      case 5:
      case 6:
        skip(8);
        index += 1;
        break;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        skip(2);
        break;
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        skip(4);
        break;
      case 15:
        skip(3);
        break;
      default:
        assert.fail(`unexpected javac constant-pool tag=${tag}`);
    }
  }
  return constants;
}

function writeStoredZip(entries) {
  const localRecords = [];
  const centralRecords = [];
  let offset = 0;
  for (const [name, bytes] of entries) {
    const fileName = Buffer.from(name, 'utf8');
    const content = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
    const checksum = crc32(content);
    const local = Buffer.alloc(30 + fileName.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(content.length, 18);
    local.writeUInt32LE(content.length, 22);
    local.writeUInt16LE(fileName.length, 26);
    local.writeUInt16LE(0, 28);
    fileName.copy(local, 30);
    localRecords.push(local, content);

    const central = Buffer.alloc(46 + fileName.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(0x0314, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(content.length, 20);
    central.writeUInt32LE(content.length, 24);
    central.writeUInt16LE(fileName.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    const directory = name.endsWith('/');
    central.writeUInt32LE(((directory ? 0o040755 : 0o100644) << 16) >>> 0, 38);
    central.writeUInt32LE(offset, 42);
    fileName.copy(central, 46);
    centralRecords.push(central);
    offset += local.length + content.length;
  }
  const centralOffset = offset;
  const centralDirectory = Buffer.concat(centralRecords);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.size, 8);
  end.writeUInt16LE(entries.size, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(centralOffset, 16);
  return Buffer.concat([...localRecords, centralDirectory, end]);
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function jsonBytes(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
}

function copyGradleFixture(destination) {
  const paths = [
    'LICENSE', 'build.gradle', 'buildscript-gradle.lockfile', 'gradle.lockfile', 'gradle.properties',
    'gradlew', 'settings.gradle', 'gradle', 'compatibility', 'src', 'presets/god', 'tools/presets',
    'build/presets/worldspeaker-god-preset-v1.zip',
    'tools/compat/verify-server-only.mjs', 'tools/compat/companion-package-freshness.mjs',
    'tools/compat/verify-companion-package-archive.mjs',
  ];
  for (const path of paths) {
    const source = join(repository, path);
    const target = join(destination, path);
    mkdirSync(dirname(target), { recursive: true });
    cpSync(source, target, { recursive: true });
  }
  const companion = join(destination, 'companion');
  mkdirSync(join(companion, 'presets'), { recursive: true });
  cpSync(join(repository, 'companion/package.json'), join(companion, 'package.json'));
  cpSync(join(repository, 'companion/compatibility'), join(companion, 'compatibility'), { recursive: true });
  cpSync(join(repository, 'companion/presets/worldspeaker-god-preset-v1.zip'),
    join(companion, 'presets/worldspeaker-god-preset-v1.zip'));
  chmodSync(join(destination, 'gradlew'), 0o755);
}

function installSyntheticFactsInFixtureOnly(fixture) {
  const buildFile = join(fixture, 'build.gradle');
  const original = readFileSync(buildFile, 'utf8');
  const start = original.indexOf('def candidateBuildFacts = {');
  const end = original.indexOf('\ndef verifyCandidateBuildEnvironment = {', start);
  assert.ok(start >= 0 && end > start, 'expected the candidate facts closure in disposable Gradle fixture');
  const java = deployment.target.java;
  const quote = (value) => JSON.stringify(value.toString());
  const synthetic = `def candidateBuildFacts = {
    // TEST FIXTURE ONLY: synthetic pinned facts let this disposable copy reach its copy boundary.
    [gradleJavaVendor: ${quote(java.vendor)}, gradleJavaRuntimeVersion: ${quote(java.runtimeVersion)},
     gradleJavaMajor: ${quote(java.major)}, compilerJavaVendor: ${quote(java.vendor)},
     compilerJavaRuntimeVersion: ${quote(java.runtimeVersion)}, compilerJavaMajor: ${quote(java.major)},
     compilerArchitecture: 'x86_64', osName: 'Linux', osArchitecture: 'x86_64',
     distributionId: 'ubuntu', distributionVersion: '24.04', kernel: 'synthetic-test-fixture']
}
`;
  writeFileSync(buildFile, `${original.slice(0, start)}${synthetic}${original.slice(end + 1)}`);
}

function installNoopApprovalCheckInFixtureOnly(fixture) {
  const buildFile = join(fixture, 'build.gradle');
  const original = readFileSync(buildFile, 'utf8');
  const start = original.indexOf('def verifyApprovedReleaseEvidence = {');
  const end = original.indexOf('\ntasks.register(\'verifyReleaseCompatibility\'', start);
  assert.ok(start >= 0 && end > start, 'expected release evidence closure in disposable Gradle fixture');
  const testBoundaryOnly = `def verifyApprovedReleaseEvidence = {
    // TEST FIXTURE ONLY: isolate the packaged-bytecode copy-boundary check.
    // This disposable replacement does not create or alter release evidence.
}
`;
  writeFileSync(buildFile, `${original.slice(0, start)}${testBoundaryOnly}${original.slice(end + 1)}`);
}

function writeFreshCompanionArchive(fixture) {
  const input = join(fixture, 'companion-fresh/package');
  mkdirSync(input, { recursive: true });
  cpSync(join(repository, 'companion/package.json'), join(input, 'package.json'));
  cpSync(join(repository, 'companion/compatibility'), join(input, 'compatibility'), { recursive: true });
  mkdirSync(join(input, 'presets'), { recursive: true });
  cpSync(join(repository, 'companion/presets/worldspeaker-god-preset-v1.zip'),
    join(input, 'presets/worldspeaker-god-preset-v1.zip'));
  const target = join(fixture, 'companion/worldspeaker-openclaw-channel-0.1.0-dev.1.tgz');
  const packed = spawnSync('tar', ['-czf', target, '-C', join(fixture, 'companion-fresh'), 'package'], {
    encoding: 'utf8', timeout: 15_000,
  });
  assert.ifError(packed.error);
  assert.equal(packed.status, 0, packed.stderr);
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
