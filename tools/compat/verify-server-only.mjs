import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { inflateRawSync } from 'node:zlib';
import { isMainThread, parentPort, Worker, workerData } from 'node:worker_threads';

const MAX_JAR_BYTES = 32 * 1024 * 1024;
const MAX_ENTRIES = 4096;
const MAX_ENTRY_BYTES = 8 * 1024 * 1024;
const MAX_TOTAL_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;
const MAX_CLASS_BYTES = 2 * 1024 * 1024;
const MAX_CLASS_COUNT = 1024;
const MAX_CONSTANT_POOL_COUNT = 8192;
const MAX_CONSTANT_UTF8_BYTES = 0xffff;
const MAX_VERIFY_MILLIS = 15_000;
const MAIN_ENTRYPOINT = 'org.worldspeaker.WorldspeakerServer';
const SERVER_OUTPUT_MIXIN_CONFIG = 'worldspeaker.mixins.json';
const PRIVATE_FILE_READER_RESOURCE = 'org/worldspeaker/compat/private-file-reader.mjs';
const PRIVATE_FILE_READER_SHA256 = 'd7f34ba48fca521fc79fcce880d7c828efb2ac37c680dbfda892e53664818405';
const SERVER_OUTPUT_MIXIN_CLASS = 'org/worldspeaker/mixin/ServerCommandSourceOutputAccessor';
const SERVER_OUTPUT_MIXIN_ENTRY = `${SERVER_OUTPUT_MIXIN_CLASS}.class`;
const REVIEWED_MIXIN_ENTRIES = Object.freeze([
  SERVER_OUTPUT_MIXIN_ENTRY,
  'org/worldspeaker/mixin/ChatMessageC2SPacketCaptureMixin.class',
  'org/worldspeaker/mixin/CommandExecutionC2SPacketCaptureMixin.class',
  'org/worldspeaker/mixin/ChatCommandSignedC2SPacketCaptureMixin.class',
  'org/worldspeaker/mixin/SignedMessageCaptureMixin.class',
  'org/worldspeaker/mixin/ServerPlayNetworkHandlerInvocationMixin.class',
].sort());
const ALLOWED_MIXIN_API_CLASSES = new Set([
  'org/spongepowered/asm/mixin/Mixin',
  'org/spongepowered/asm/mixin/Unique',
  'org/spongepowered/asm/mixin/gen/Accessor',
  'org/spongepowered/asm/mixin/injection/At',
  'org/spongepowered/asm/mixin/injection/Inject',
  'org/spongepowered/asm/mixin/injection/ModifyArgs',
  'org/spongepowered/asm/mixin/injection/invoke/arg/Args',
  'org/spongepowered/asm/mixin/injection/callback/CallbackInfo',
  'org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable',
]);
const REVIEWED_MIXIN_NAMES = Object.freeze([
  'ServerCommandSourceOutputAccessor',
  'ChatMessageC2SPacketCaptureMixin',
  'CommandExecutionC2SPacketCaptureMixin',
  'ChatCommandSignedC2SPacketCaptureMixin',
  'SignedMessageCaptureMixin',
  'ServerPlayNetworkHandlerInvocationMixin',
]);
const EXPECTED_MIXIN_REFMAP = {
  mappings: {
    [SERVER_OUTPUT_MIXIN_CLASS]: { output: 'field_9819{,1}:Lnet/minecraft/class_2165;' },
    'org/worldspeaker/mixin/ServerPlayNetworkHandlerInvocationMixin': {
      'Lnet/minecraft/server/network/ServerPlayNetworkHandler;validateMessage(Ljava/lang/String;Ljava/lang/Runnable;)V': 'Lnet/minecraft/class_3244;method_44337(Ljava/lang/String;Ljava/lang/Runnable;)V',
      getSignedMessage: 'Lnet/minecraft/class_3244;method_45011(Lnet/minecraft/class_2797;Lnet/minecraft/class_7635;)Lnet/minecraft/class_7471;',
      onChatCommandSigned: 'Lnet/minecraft/class_3244;method_58580(Lnet/minecraft/class_9449;)V',
      onChatMessage: 'Lnet/minecraft/class_3244;method_12048(Lnet/minecraft/class_2797;)V',
      onCommandExecution: 'Lnet/minecraft/class_3244;method_43667(Lnet/minecraft/class_7472;)V',
      onDisconnected: 'Lnet/minecraft/class_3244;method_10839(Lnet/minecraft/class_9812;)V',
    },
    'org/worldspeaker/mixin/SignedMessageCaptureMixin': {
      stripSignature: 'Lnet/minecraft/class_7471;method_55625()Lnet/minecraft/class_7471;',
      withFilterMask: 'Lnet/minecraft/class_7471;method_45097(Lnet/minecraft/class_7649;)Lnet/minecraft/class_7471;',
      withFilterMaskEnabled: 'Lnet/minecraft/class_7471;method_45099(Z)Lnet/minecraft/class_7471;',
      withUnsignedContent: 'Lnet/minecraft/class_7471;method_44863(Lnet/minecraft/class_2561;)Lnet/minecraft/class_7471;',
      withoutUnsigned: 'Lnet/minecraft/class_7471;method_44607()Lnet/minecraft/class_7471;',
    },
  },
};
EXPECTED_MIXIN_REFMAP.data = { 'named:intermediary': EXPECTED_MIXIN_REFMAP.mappings };
const REPOSITORY_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

// Reviewed server API surface for this exact slice. Class/member aliases were
// resolved from Yarn 1.21.1+build.3 (intermediary -> named). The small Mojang
// mapped class names and Fabric/Brigadier APIs are identity mappings.
const ALLOWED_API_CLASSES = new Set([
  'com/mojang/brigadier/Command',
  'com/mojang/brigadier/CommandDispatcher',
  'com/mojang/brigadier/arguments/ArgumentType',
  'com/mojang/brigadier/arguments/StringArgumentType',
  'com/mojang/brigadier/builder/ArgumentBuilder',
  'com/mojang/brigadier/builder/LiteralArgumentBuilder',
  'com/mojang/brigadier/builder/RequiredArgumentBuilder',
  'com/mojang/brigadier/context/CommandContext',
  'com/mojang/brigadier/exceptions/CommandSyntaxException',
  'com/mojang/brigadier/tree/LiteralCommandNode',
  'com/mojang/authlib/GameProfile',
  'net/fabricmc/api/ModInitializer',
  'net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback',
  'net/fabricmc/fabric/api/event/Event',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents$ServerStarted',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents$ServerStopping',
  'net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents',
  'net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents$AfterPlayerChange',
  'net/fabricmc/fabric/api/entity/event/v1/ServerLivingEntityEvents',
  'net/fabricmc/fabric/api/entity/event/v1/ServerLivingEntityEvents$AfterDeath',
  'net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents',
  'net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents$AfterRespawn',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents$EndTick',
  'net/fabricmc/fabric/api/message/v1/ServerMessageEvents',
  'net/fabricmc/fabric/api/message/v1/ServerMessageEvents$ChatMessage',
  'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents$Disconnect',
  'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents$Join',
  'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents',
  'net/fabricmc/fabric/api/networking/v1/PacketSender',
  'net/fabricmc/loader/api/FabricLoader',
  'net/fabricmc/loader/api/ModContainer',
  'net/fabricmc/loader/api/Version',
  'net/fabricmc/loader/api/metadata/ModMetadata',
  'net/minecraft/command/CommandRegistryAccess',
  'net/minecraft/network/message/FilterMask',
  'net/minecraft/network/message/LastSeenMessageList',
  'net/minecraft/network/message/MessageType',
  'net/minecraft/network/message/MessageType$Parameters',
  'net/minecraft/network/DisconnectionInfo',
  'net/minecraft/network/message/SignedMessage',
  'net/minecraft/network/packet/c2s/play/ChatMessageC2SPacket',
  'net/minecraft/network/packet/c2s/play/CommandExecutionC2SPacket',
  'net/minecraft/network/packet/c2s/play/ChatCommandSignedC2SPacket',
  'net/minecraft/registry/RegistryKey',
  'net/minecraft/registry/entry/RegistryEntry',
  'net/minecraft/server/MinecraftServer',
  'net/minecraft/server/PlayerManager',
  'net/minecraft/entity/Entity',
  'net/minecraft/entity/LivingEntity',
  'net/minecraft/entity/damage/DamageSource',
  'net/minecraft/server/command/CommandManager',
  'net/minecraft/server/command/CommandManager$RegistrationEnvironment',
  'net/minecraft/server/command/CommandOutput',
  'net/minecraft/server/command/ServerCommandSource',
  'net/minecraft/server/world/ServerWorld',
  'net/minecraft/server/world/ServerChunkManager',
  'net/minecraft/world/chunk/ChunkManager',
  'net/minecraft/util/math/Position',
  'net/minecraft/util/thread/ThreadExecutor',
  'net/minecraft/server/network/ServerPlayNetworkHandler',
  'net/minecraft/server/network/ServerPlayerEntity',
  'net/minecraft/text/MutableText',
  'net/minecraft/text/Text',
  'net/minecraft/util/Identifier',
  'net/minecraft/util/math/BlockPos',
  'net/minecraft/util/math/ChunkPos',
  'net/minecraft/util/math/Vec3d',
  'net/minecraft/util/math/Vec3i',
  'net/minecraft/world/World',
  'net/minecraft/world/border/WorldBorder',
]);
const ALLOWED_API_MEMBERS = new Set([
  'com/mojang/brigadier/CommandDispatcher#register(Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;)Lcom/mojang/brigadier/tree/LiteralCommandNode;',
  'com/mojang/brigadier/arguments/StringArgumentType#getString(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Ljava/lang/String;',
  'com/mojang/brigadier/arguments/StringArgumentType#greedyString()Lcom/mojang/brigadier/arguments/StringArgumentType;',
  'com/mojang/brigadier/builder/LiteralArgumentBuilder#literal(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;',
  'com/mojang/brigadier/builder/LiteralArgumentBuilder#executes(Lcom/mojang/brigadier/Command;)Lcom/mojang/brigadier/builder/ArgumentBuilder;',
  'com/mojang/brigadier/builder/LiteralArgumentBuilder#then(Lcom/mojang/brigadier/builder/ArgumentBuilder;)Lcom/mojang/brigadier/builder/ArgumentBuilder;',
  'com/mojang/brigadier/builder/RequiredArgumentBuilder#argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;',
  'com/mojang/brigadier/builder/RequiredArgumentBuilder#executes(Lcom/mojang/brigadier/Command;)Lcom/mojang/brigadier/builder/ArgumentBuilder;',
  'com/mojang/brigadier/context/CommandContext#getInput()Ljava/lang/String;',
  'com/mojang/brigadier/context/CommandContext#getSource()Ljava/lang/Object;',
  'com/mojang/authlib/GameProfile#getId()Ljava/util/UUID;',
  'com/mojang/authlib/GameProfile#getName()Ljava/lang/String;',
  'net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback#EVENTLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/event/Event#register(Ljava/lang/Object;)V',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents#SERVER_STARTEDLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents#SERVER_STOPPINGLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents#AFTER_PLAYER_CHANGE_WORLDLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents$AfterPlayerChange#afterChangeWorld(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/server/world/ServerWorld;)V',
  'net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents#AFTER_RESPAWNLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents$AfterRespawn#afterRespawn(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ServerPlayerEntity;Z)V',
  'net/fabricmc/fabric/api/entity/event/v1/ServerLivingEntityEvents#AFTER_DEATHLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/entity/event/v1/ServerLivingEntityEvents$AfterDeath#afterDeath(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/damage/DamageSource;)V',
  'net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents#END_SERVER_TICKLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/message/v1/ServerMessageEvents#CHAT_MESSAGELnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents#DISCONNECTLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents#JOINLnet/fabricmc/fabric/api/event/Event;',
  'net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents$Join#onPlayReady(Lnet/minecraft/server/network/ServerPlayNetworkHandler;Lnet/fabricmc/fabric/api/networking/v1/PacketSender;Lnet/minecraft/server/MinecraftServer;)V',
  'net/minecraft/entity/Entity#isAlive()Z',
  'net/minecraft/entity/Entity#getPos()Lnet/minecraft/util/math/Vec3d;',
  'net/minecraft/server/network/ServerPlayerEntity#isAlive()Z',
  'net/minecraft/server/network/ServerPlayerEntity#isSpectator()Z',
  'net/minecraft/server/network/ServerPlayerEntity#getPos()Lnet/minecraft/util/math/Vec3d;',
  'net/minecraft/server/network/ServerPlayerEntity#getServerWorld()Lnet/minecraft/server/world/ServerWorld;',
  'net/minecraft/server/MinecraftServer#getWorld(Lnet/minecraft/registry/RegistryKey;)Lnet/minecraft/server/world/ServerWorld;',
  'net/minecraft/server/MinecraftServer#isOnThread()Z',
  'net/minecraft/server/world/ServerWorld#getChunkManager()Lnet/minecraft/server/world/ServerChunkManager;',
  'net/minecraft/server/world/ServerWorld#getWorldBorder()Lnet/minecraft/world/border/WorldBorder;',
  'net/minecraft/server/world/ServerWorld#shouldTickEntity(Lnet/minecraft/util/math/BlockPos;)Z',
  'net/minecraft/server/world/ServerChunkManager#isChunkLoaded(II)Z',
  'net/minecraft/server/world/ServerChunkManager#isTickingFutureReady(J)Z',
  'net/minecraft/world/chunk/ChunkManager#isChunkLoaded(II)Z',
  'net/minecraft/util/math/BlockPos#ofFloored(DDD)Lnet/minecraft/util/math/BlockPos;',
  'net/minecraft/util/math/BlockPos#getX()I',
  'net/minecraft/util/math/BlockPos#getY()I',
  'net/minecraft/util/math/BlockPos#getZ()I',
  'net/minecraft/util/math/Vec3d#getX()D',
  'net/minecraft/util/math/Vec3d#getY()D',
  'net/minecraft/util/math/Vec3d#getZ()D',
  'net/minecraft/util/math/Vec3i#getX()I',
  'net/minecraft/util/math/Vec3i#getZ()I',
  'net/minecraft/util/math/Position#getX()D',
  'net/minecraft/util/math/Position#getY()D',
  'net/minecraft/util/math/Position#getZ()D',
  'net/minecraft/util/math/ChunkPos#toLong(II)J',
  'net/minecraft/world/World#getWorldBorder()Lnet/minecraft/world/border/WorldBorder;',
  'net/minecraft/world/border/WorldBorder#getBoundWest()D',
  'net/minecraft/world/border/WorldBorder#getBoundEast()D',
  'net/minecraft/world/border/WorldBorder#getBoundNorth()D',
  'net/minecraft/world/border/WorldBorder#getBoundSouth()D',
  'net/minecraft/entity/Entity#getBlockX()I',
  'net/minecraft/entity/Entity#getBlockY()I',
  'net/minecraft/entity/Entity#getBlockZ()I',
  'net/minecraft/server/network/ServerPlayerEntity#getBlockX()I',
  'net/minecraft/server/network/ServerPlayerEntity#getBlockY()I',
  'net/minecraft/server/network/ServerPlayerEntity#getBlockZ()I',
  'net/minecraft/server/world/ServerWorld#getRegistryKey()Lnet/minecraft/registry/RegistryKey;',
  'net/fabricmc/loader/api/FabricLoader#getConfigDir()Ljava/nio/file/Path;',
  'net/fabricmc/loader/api/FabricLoader#getInstance()Lnet/fabricmc/loader/api/FabricLoader;',
  'net/fabricmc/loader/api/FabricLoader#getModContainer(Ljava/lang/String;)Ljava/util/Optional;',
  'net/fabricmc/loader/api/ModContainer#getMetadata()Lnet/fabricmc/loader/api/metadata/ModMetadata;',
  'net/fabricmc/loader/api/Version#getFriendlyString()Ljava/lang/String;',
  'net/fabricmc/loader/api/metadata/ModMetadata#getVersion()Lnet/fabricmc/loader/api/Version;',
  'net/minecraft/server/command/CommandManager#argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;',
  'net/minecraft/server/command/CommandManager#literal(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;',
  'net/minecraft/server/command/ServerCommandSource#getPlayer()Lnet/minecraft/server/network/ServerPlayerEntity;',
  'net/minecraft/server/command/ServerCommandSource#getEntity()Lnet/minecraft/entity/Entity;',
  'net/minecraft/server/command/ServerCommandSource#getWorld()Lnet/minecraft/server/world/ServerWorld;',
  'net/minecraft/server/command/ServerCommandSource#sendError(Lnet/minecraft/text/Text;)V',
  'net/minecraft/server/network/ServerPlayerEntity#networkHandlerLnet/minecraft/server/network/ServerPlayNetworkHandler;',
  'net/minecraft/server/network/ServerPlayNetworkHandler#playerLnet/minecraft/server/network/ServerPlayerEntity;',
  'net/minecraft/server/network/ServerPlayNetworkHandler#isConnectionOpen()Z',
  'net/minecraft/server/network/ServerPlayNetworkHandler#onDisconnected(Lnet/minecraft/network/DisconnectionInfo;)V',
  'net/minecraft/server/network/ServerPlayerEntity#getWorld()Lnet/minecraft/world/World;',
  'net/minecraft/server/network/ServerPlayerEntity#getUuid()Ljava/util/UUID;',
  'net/minecraft/server/network/ServerPlayerEntity#getServer()Lnet/minecraft/server/MinecraftServer;',
  'net/minecraft/server/network/ServerPlayerEntity#getCommandSource()Lnet/minecraft/server/command/ServerCommandSource;',
  'net/minecraft/server/network/ServerPlayerEntity#getGameProfile()Lcom/mojang/authlib/GameProfile;',
  'net/minecraft/server/network/ServerPlayerEntity#sendMessage(Lnet/minecraft/text/Text;Z)V',
  'net/minecraft/registry/RegistryKey#getValue()Lnet/minecraft/util/Identifier;',
  'net/minecraft/server/PlayerManager#getPlayer(Ljava/util/UUID;)Lnet/minecraft/server/network/ServerPlayerEntity;',
  'net/minecraft/server/PlayerManager#getPlayerList()Ljava/util/List;',
  'net/minecraft/server/MinecraftServer#getPlayerManager()Lnet/minecraft/server/PlayerManager;',
  'net/minecraft/server/MinecraftServer#isOnlineMode()Z',
  'net/minecraft/server/command/ServerCommandSource#getServer()Lnet/minecraft/server/MinecraftServer;',
  'net/minecraft/network/message/FilterMask#isPassThrough()Z',
  'net/minecraft/network/message/MessageType#CHATLnet/minecraft/registry/RegistryKey;',
  'net/minecraft/network/message/MessageType$Parameters#type()Lnet/minecraft/registry/entry/RegistryEntry;',
  'net/minecraft/network/message/SignedMessage#filterMask()Lnet/minecraft/network/message/FilterMask;',
  'net/minecraft/network/message/SignedMessage#getSender()Ljava/util/UUID;',
  'net/minecraft/network/message/SignedMessage#getSignedContent()Ljava/lang/String;',
  'net/minecraft/network/message/SignedMessage#hasSignature()Z',
  'net/minecraft/registry/entry/RegistryEntry#matchesKey(Lnet/minecraft/registry/RegistryKey;)Z',
  'net/minecraft/text/Text#literal(Ljava/lang/String;)Lnet/minecraft/text/MutableText;',
  'net/minecraft/util/Identifier#toString()Ljava/lang/String;',
  'net/minecraft/world/World#getRegistryKey()Lnet/minecraft/registry/RegistryKey;',
]);
const CLASS_INTERMEDIARY_TO_NAMED = new Map([
  ['net/minecraft/class_9812', 'net/minecraft/network/DisconnectionInfo'],
  ['net/minecraft/class_1297', 'net/minecraft/entity/Entity'],
  ['net/minecraft/class_1309', 'net/minecraft/entity/LivingEntity'],
  ['net/minecraft/class_1282', 'net/minecraft/entity/damage/DamageSource'],
  ['net/minecraft/class_3218', 'net/minecraft/server/world/ServerWorld'],
  ['net/minecraft/class_3215', 'net/minecraft/server/world/ServerChunkManager'],
  ['net/minecraft/class_2802', 'net/minecraft/world/chunk/ChunkManager'],
  ['net/minecraft/class_2784', 'net/minecraft/world/border/WorldBorder'],
  ['net/minecraft/class_2338', 'net/minecraft/util/math/BlockPos'],
  ['net/minecraft/class_1923', 'net/minecraft/util/math/ChunkPos'],
  ['net/minecraft/class_243', 'net/minecraft/util/math/Vec3d'],
  ['net/minecraft/class_2382', 'net/minecraft/util/math/Vec3i'],
  ['net/minecraft/class_2374', 'net/minecraft/util/math/Position'],
  ['net/minecraft/class_1255', 'net/minecraft/util/thread/ThreadExecutor'],
  ['net/minecraft/class_1937', 'net/minecraft/world/World'],
  ['net/minecraft/class_2556', 'net/minecraft/network/message/MessageType'],
  ['net/minecraft/class_2556$class_7602', 'net/minecraft/network/message/MessageType$Parameters'],
  ['net/minecraft/class_7649', 'net/minecraft/network/message/FilterMask'],
  ['net/minecraft/class_7635', 'net/minecraft/network/message/LastSeenMessageList'],
  ['net/minecraft/class_6880', 'net/minecraft/registry/entry/RegistryEntry'],
  ['net/minecraft/class_2168', 'net/minecraft/server/command/ServerCommandSource'],
  ['net/minecraft/class_2165', 'net/minecraft/server/command/CommandOutput'],
  ['net/minecraft/class_2170', 'net/minecraft/server/command/CommandManager'],
  ['net/minecraft/class_2170$class_5364', 'net/minecraft/server/command/CommandManager$RegistrationEnvironment'],
  ['net/minecraft/class_2561', 'net/minecraft/text/Text'],
  ['net/minecraft/class_2960', 'net/minecraft/util/Identifier'],
  ['net/minecraft/class_3222', 'net/minecraft/server/network/ServerPlayerEntity'],
  ['net/minecraft/class_3244', 'net/minecraft/server/network/ServerPlayNetworkHandler'],
  ['net/minecraft/class_3324', 'net/minecraft/server/PlayerManager'],
  ['net/minecraft/class_5250', 'net/minecraft/text/MutableText'],
  ['net/minecraft/class_5321', 'net/minecraft/registry/RegistryKey'],
  ['net/minecraft/class_7157', 'net/minecraft/command/CommandRegistryAccess'],
  ['net/minecraft/class_2378', 'net/minecraft/registry/Registry'],
  ['net/minecraft/class_7471', 'net/minecraft/network/message/SignedMessage'],
  ['net/minecraft/class_2797', 'net/minecraft/network/packet/c2s/play/ChatMessageC2SPacket'],
  ['net/minecraft/class_7472', 'net/minecraft/network/packet/c2s/play/CommandExecutionC2SPacket'],
  ['net/minecraft/class_9449', 'net/minecraft/network/packet/c2s/play/ChatCommandSignedC2SPacket'],
  ['net/minecraft/class_7469', 'net/minecraft/network/message/MessageSignatureData'],
  ['net/minecraft/class_8710', 'net/minecraft/network/packet/CustomPayload'],
  ['net/minecraft/class_2658', 'net/minecraft/network/packet/s2c/common/CustomPayloadS2CPacket'],
  ['net/minecraft/class_2817', 'net/minecraft/network/packet/c2s/common/CustomPayloadC2SPacket'],
  ['net/minecraft/class_8610', 'net/minecraft/server/network/ServerConfigurationNetworkHandler'],
  ['net/minecraft/class_8609', 'net/minecraft/server/network/ServerCommonNetworkHandler'],
  ['net/minecraft/class_8612', 'net/minecraft/server/network/SendResourcePackTask'],
  ['net/minecraft/class_2720', 'net/minecraft/network/packet/s2c/common/ResourcePackSendS2CPacket'],
]);
const MEMBER_INTERMEDIARY_TO_NAMED = new Map([
  ['net/minecraft/class_1937#method_27983()Lnet/minecraft/class_5321;', 'getRegistryKey'],
  ['net/minecraft/class_3218#method_27983()Lnet/minecraft/class_5321;', 'getRegistryKey'],
  ['net/minecraft/class_2556#field_11737Lnet/minecraft/class_5321;', 'CHAT'],
  ['net/minecraft/class_2556$class_7602#comp_919()Lnet/minecraft/class_6880;', 'type'],
  ['net/minecraft/class_6880#method_40225(Lnet/minecraft/class_5321;)Z', 'matchesKey'],
  ['net/minecraft/class_7649#method_45087()Z', 'isPassThrough'],
  ['net/minecraft/class_7471#comp_981()Lnet/minecraft/class_7649;', 'filterMask'],
  ['net/minecraft/class_7471#method_46292()Ljava/util/UUID;', 'getSender'],
  ['net/minecraft/class_7471#method_44862()Ljava/lang/String;', 'getSignedContent'],
  ['net/minecraft/class_7471#method_46294()Z', 'hasSignature'],
  ['net/minecraft/class_2168#method_44023()Lnet/minecraft/class_3222;', 'getPlayer'],
  ['net/minecraft/class_2168#method_9228()Lnet/minecraft/class_1297;', 'getEntity'],
  ['net/minecraft/class_2168#method_9225()Lnet/minecraft/class_3218;', 'getWorld'],
  ['net/minecraft/class_2168#method_9213(Lnet/minecraft/class_2561;)V', 'sendError'],
  ['net/minecraft/class_2168#method_9211()Lnet/minecraft/server/MinecraftServer;', 'getServer'],
  ['net/minecraft/server/MinecraftServer#method_3828()Z', 'isOnlineMode'],
  ['net/minecraft/class_2170#method_9244(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;', 'argument'],
  ['net/minecraft/class_2170#method_9247(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;', 'literal'],
  ['net/minecraft/class_2561#method_43470(Ljava/lang/String;)Lnet/minecraft/class_5250;', 'literal'],
  ['net/minecraft/class_3222#field_13987Lnet/minecraft/class_3244;', 'networkHandler'],
  ['net/minecraft/class_3244#field_14140Lnet/minecraft/class_3222;', 'player'],
  ['net/minecraft/class_3244#method_48106()Z', 'isConnectionOpen'],
  ['net/minecraft/class_3222#method_37908()Lnet/minecraft/class_1937;', 'getWorld'],
  ['net/minecraft/class_3222#method_5805()Z', 'isAlive'],
  ['net/minecraft/class_3222#method_31477()I', 'getBlockX'],
  ['net/minecraft/class_3222#method_31478()I', 'getBlockY'],
  ['net/minecraft/class_3222#method_31479()I', 'getBlockZ'],
  ['net/minecraft/class_3222#method_5667()Ljava/util/UUID;', 'getUuid'],
  ['net/minecraft/class_3222#method_5682()Lnet/minecraft/server/MinecraftServer;', 'getServer'],
  ['net/minecraft/class_3222#method_5671()Lnet/minecraft/class_2168;', 'getCommandSource'],
  ['net/minecraft/class_3222#method_51469()Lnet/minecraft/class_3218;', 'getServerWorld'],
  ['net/minecraft/class_3222#method_19538()Lnet/minecraft/class_243;', 'getPos'],
  ['net/minecraft/class_3222#method_7325()Z', 'isSpectator'],
  ['net/minecraft/server/MinecraftServer#method_3847(Lnet/minecraft/class_5321;)Lnet/minecraft/class_3218;', 'getWorld'],
  ['net/minecraft/class_3218#method_14178()Lnet/minecraft/class_3215;', 'getChunkManager'],
  ['net/minecraft/class_3218#method_8621()Lnet/minecraft/class_2784;', 'getWorldBorder'],
  ['net/minecraft/class_3218#method_37118(Lnet/minecraft/class_2338;)Z', 'shouldTickEntity'],
  ['net/minecraft/class_3215#method_12123(II)Z', 'isChunkLoaded'],
  ['net/minecraft/class_2802#method_12123(II)Z', 'isChunkLoaded'],
  ['net/minecraft/class_3215#method_37114(J)Z', 'isTickingFutureReady'],
  ['net/minecraft/class_2338#method_49637(DDD)Lnet/minecraft/class_2338;', 'ofFloored'],
  ['net/minecraft/class_2338#method_10263()I', 'getX'],
  ['net/minecraft/class_2338#method_10264()I', 'getY'],
  ['net/minecraft/class_2338#method_10260()I', 'getZ'],
  ['net/minecraft/class_243#method_10216()D', 'getX'],
  ['net/minecraft/class_243#method_10214()D', 'getY'],
  ['net/minecraft/class_243#method_10215()D', 'getZ'],
  ['net/minecraft/class_2374#method_10216()D', 'getX'],
  ['net/minecraft/class_2374#method_10214()D', 'getY'],
  ['net/minecraft/class_2374#method_10215()D', 'getZ'],
  ['net/minecraft/class_2382#method_10263()I', 'getX'],
  ['net/minecraft/class_2382#method_10264()I', 'getY'],
  ['net/minecraft/class_2382#method_10260()I', 'getZ'],
  ['net/minecraft/class_1923#method_8331(II)J', 'toLong'],
  ['net/minecraft/class_1937#method_8621()Lnet/minecraft/class_2784;', 'getWorldBorder'],
  ['net/minecraft/class_2784#method_11976()D', 'getBoundWest'],
  ['net/minecraft/class_2784#method_11963()D', 'getBoundEast'],
  ['net/minecraft/class_2784#method_11958()D', 'getBoundNorth'],
  ['net/minecraft/class_2784#method_11977()D', 'getBoundSouth'],
  ['net/minecraft/class_1255#method_18854()Z', 'isOnThread'],
  ['net/minecraft/server/MinecraftServer#method_18854()Z', 'isOnThread'],
  ['net/minecraft/class_3222#method_7334()Lcom/mojang/authlib/GameProfile;', 'getGameProfile'],
  ['net/minecraft/class_3222#method_7353(Lnet/minecraft/class_2561;Z)V', 'sendMessage'],
  ['net/minecraft/class_3324#method_14602(Ljava/util/UUID;)Lnet/minecraft/class_3222;', 'getPlayer'],
  ['net/minecraft/class_3324#method_14571()Ljava/util/List;', 'getPlayerList'],
  ['net/minecraft/class_5321#method_29177()Lnet/minecraft/class_2960;', 'getValue'],
  ['net/minecraft/server/MinecraftServer#method_3760()Lnet/minecraft/class_3324;', 'getPlayerManager'],
  ['net/minecraft/class_8610#method_52411()V', 'queueSendResourcePackTask'],
  ['net/minecraft/class_2378#method_10230(Lnet/minecraft/class_2378;Lnet/minecraft/class_2960;Ljava/lang/Object;)Ljava/lang/Object;', 'register'],
  ['net/minecraft/class_2378#method_39197(Lnet/minecraft/class_2378;Lnet/minecraft/class_5321;Ljava/lang/Object;)Ljava/lang/Object;', 'register'],
  ['net/minecraft/class_2378#method_10226(Lnet/minecraft/class_2378;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;', 'register'],
]);

const crcTable = makeCrcTable();

export function verifyJar(jarPath) {
  const startedAt = performance.now();
  const stat = statSync(jarPath);
  if (!stat.isFile() || stat.size < 22 || stat.size > MAX_JAR_BYTES) {
    reject('archive-size-limit', `bytes=${stat.size}; allowed=22..${MAX_JAR_BYTES}`);
  }
  const bytes = readFileSync(jarPath);
  if (bytes.length !== stat.size || bytes.length > MAX_JAR_BYTES) reject('archive-size-limit', `bytes=${bytes.length}; allowed=22..${MAX_JAR_BYTES}`);
  checkDeadline(startedAt);
  const entries = readZipEntries(bytes, startedAt);
  const required = [
    'fabric.mod.json', 'worldspeaker-deployment.json', 'worldspeaker-deployment.properties',
    'worldspeaker-deployment.sha256', SERVER_OUTPUT_MIXIN_CONFIG, 'worldspeaker-refmap.json',
    PRIVATE_FILE_READER_RESOURCE,
  ];
  for (const name of required) if (!entries.has(name)) reject('required-entry-missing', `entry=${name}`);
  const reader = entries.get(PRIVATE_FILE_READER_RESOURCE);
  if (reader.length < 1 || reader.length > 16_384 ||
      createHash('sha256').update(reader).digest('hex') !== PRIVATE_FILE_READER_SHA256) {
    reject('private-reader-resource-invalid', `entry=${PRIVATE_FILE_READER_RESOURCE}`);
  }

  inspectPaths(entries);
  inspectMetadata(entries);

  const classes = [...entries.entries()].filter(([name]) => name.endsWith('.class'));
  if (classes.length === 0 || classes.length > MAX_CLASS_COUNT) {
    reject('class-count-limit', `classes=${classes.length}; allowed=1..${MAX_CLASS_COUNT}`);
  }
  const sourceMixinEntries = classes.map(([name]) => name).filter((name) => name.startsWith('org/worldspeaker/mixin/'));
  if (stableJson(sourceMixinEntries.sort()) !== stableJson(REVIEWED_MIXIN_ENTRIES)) {
    reject('mixin-class-boundary-invalid', `entries=${sourceMixinEntries.join(',') || 'none'}; expected=${REVIEWED_MIXIN_ENTRIES.join(',')}`);
  }

  const refs = { classReferences: [], members: [] };
  for (const [name, classBytes] of classes) {
    checkDeadline(startedAt);
    if (classBytes.length > MAX_CLASS_BYTES) reject('class-size-limit', `entry=${name}; bytes=${classBytes.length}`);
    if (name.startsWith('net/minecraft/client/') || name.startsWith('net/fabricmc/fabric/api/client/') || name.startsWith('org/worldspeaker/client/')) {
      reject('client-class', `entry=${name}`);
    }
    const parsed = parseClassFile(classBytes, name);
    if (name === 'org/worldspeaker/mixin/ServerPlayNetworkHandlerInvocationMixin.class') {
      inspectCommandCaptureHandlers(parsed, name);
    }
    if (name === SERVER_OUTPUT_MIXIN_ENTRY) inspectServerOutputAccessorClass(parsed, name);
    else if (REVIEWED_MIXIN_ENTRIES.includes(name) && !parsed.constants.includes('Lorg/spongepowered/asm/mixin/Mixin;')) {
      reject('mixin-class-annotation-missing', `entry=${name}; expected @Mixin`);
    }
    for (const className of parsed.classes) refs.classReferences.push({ name: className, entry: name });
    for (const constant of parsed.constants) {
      if (constant.startsWith('Lorg/spongepowered/asm/mixin/')) {
        const mixinClass = constant.slice(1).split(/[;<]/u, 1)[0];
        refs.classReferences.push({ name: mixinClass, entry: name });
      }
    }
    refs.members.push(...parsed.members.map((member) => ({ ...member, entry: name })));
  }
  inspectApiReferences(refs);

  const sha256 = createHash('sha256').update(bytes).digest('hex');
  return { bytes: bytes.length, sha256, entries: entries.size, classes: classes.length };
}

export function readZipEntries(archive, startedAt = performance.now()) {
  if (!Buffer.isBuffer(archive) || archive.length < 22 || archive.length > MAX_JAR_BYTES) {
    reject('archive-size-limit', `bytes=${archive?.length ?? 'invalid'}; allowed=22..${MAX_JAR_BYTES}`);
  }
  const endRecord = findEndRecord(archive);
  if (endRecord.diskNumber !== 0 || endRecord.centralDisk !== 0 || endRecord.diskEntries !== endRecord.entryCount) {
    reject('multi-disk-zip', 'multi-disk and ZIP64 archives are not supported');
  }
  if (endRecord.entryCount > MAX_ENTRIES || endRecord.entryCount === 0xffff || endRecord.centralSize === 0xffffffff || endRecord.centralOffset === 0xffffffff) {
    reject('entry-count-limit', `entries=${endRecord.entryCount}; allowed=0..${MAX_ENTRIES}`);
  }
  if (endRecord.centralOffset + endRecord.centralSize !== endRecord.offset) {
    reject('invalid-central-directory', 'central directory bounds do not match end record');
  }

  const entries = new Map();
  const localRanges = [];
  let totalUncompressed = 0;
  let cursor = endRecord.centralOffset;
  for (let index = 0; index < endRecord.entryCount; index += 1) {
    checkDeadline(startedAt);
    requireRange(archive, cursor, 46, 'central-directory-truncated');
    if (archive.readUInt32LE(cursor) !== 0x02014b50) reject('invalid-central-directory', `record=${index}`);
    const flags = archive.readUInt16LE(cursor + 8);
    const method = archive.readUInt16LE(cursor + 10);
    const crc = archive.readUInt32LE(cursor + 16);
    const compressedSize = archive.readUInt32LE(cursor + 20);
    const uncompressedSize = archive.readUInt32LE(cursor + 24);
    const nameLength = archive.readUInt16LE(cursor + 28);
    const extraLength = archive.readUInt16LE(cursor + 30);
    const commentLength = archive.readUInt16LE(cursor + 32);
    const diskStart = archive.readUInt16LE(cursor + 34);
    const externalAttributes = archive.readUInt32LE(cursor + 38);
    const localOffset = archive.readUInt32LE(cursor + 42);
    const recordLength = 46 + nameLength + extraLength + commentLength;
    requireRange(archive, cursor, recordLength, 'central-directory-truncated');
    if (diskStart !== 0 || compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) {
      reject('zip64-or-multi-disk-entry', `record=${index}`);
    }
    if (flags & 1) reject('encrypted-entry', `record=${index}`);
    if (method !== 0 && method !== 8) reject('unsupported-compression', `record=${index}; method=${method}`);
    if (compressedSize > MAX_ENTRY_BYTES || uncompressedSize > MAX_ENTRY_BYTES) {
      reject('entry-size-limit', `record=${index}; compressed=${compressedSize}; uncompressed=${uncompressedSize}`);
    }
    totalUncompressed += uncompressedSize;
    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) reject('expanded-size-limit', `expanded=${totalUncompressed}`);

    const rawName = archive.subarray(cursor + 46, cursor + 46 + nameLength);
    const name = decodeEntryName(rawName, flags, index);
    validateEntryName(name);
    if (entries.has(name)) reject('duplicate-entry', `entry=${name}`);
    const extra = archive.subarray(cursor + 46 + nameLength, cursor + 46 + nameLength + extraLength);
    rejectZip64Extra(extra, name);
    const unixMode = externalAttributes >>> 16;
    if ((unixMode & 0xf000) === 0xa000) reject('symbolic-link-entry', `entry=${name}`);

    const local = readLocalEntry(archive, localOffset, name, flags, method, compressedSize, uncompressedSize, crc, endRecord.centralOffset);
    const entryRangeEnd = local.dataOffset + compressedSize + local.descriptorLength;
    localRanges.push([localOffset, entryRangeEnd, name]);
    const compressed = archive.subarray(local.dataOffset, local.dataOffset + compressedSize);
    let content;
    try {
      content = method === 0 ? Buffer.from(compressed) : inflateRawSync(compressed, { maxOutputLength: uncompressedSize + 1 });
    } catch (error) {
      reject('invalid-compressed-entry', `entry=${name}; reason=${safeReason(error)}`);
    }
    if (content.length !== uncompressedSize) reject('entry-size-mismatch', `entry=${name}; observed=${content.length}; declared=${uncompressedSize}`);
    if (crc32(content) !== crc) reject('entry-crc-mismatch', `entry=${name}`);
    if (!name.endsWith('/') && content.length > 0) entries.set(name, content);
    else entries.set(name, Buffer.alloc(0));
    cursor += recordLength;
  }
  if (cursor !== endRecord.offset) reject('invalid-central-directory', `end=${cursor}; expected=${endRecord.offset}`);
  localRanges.sort((left, right) => left[0] - right[0]);
  for (let i = 1; i < localRanges.length; i += 1) {
    if (localRanges[i][0] < localRanges[i - 1][1]) reject('overlapping-local-entries', `entry=${localRanges[i][2]}`);
  }
  checkDeadline(startedAt);
  return entries;
}

export function parseClassFile(bytes, entryName = '<class>') {
  const reader = new ClassReader(bytes, entryName);
  const magic = reader.u4();
  if (magic !== 0xcafebabe) reject('malformed-class', `entry=${entryName}; bad magic`);
  reader.u2(); // minor version
  const major = reader.u2();
  if (major < 45 || major > 70) reject('malformed-class', `entry=${entryName}; unsupported class version=${major}`);
  const cpCount = reader.u2();
  if (cpCount < 1 || cpCount > MAX_CONSTANT_POOL_COUNT) reject('constant-pool-limit', `entry=${entryName}; count=${cpCount}`);
  const cp = new Array(cpCount);
  for (let index = 1; index < cpCount; index += 1) {
    const tag = reader.u1();
    switch (tag) {
      case 1: {
        const length = reader.u2();
        cp[index] = { tag, value: decodeModifiedUtf8(reader.bytes(length), entryName, index) };
        break;
      }
      case 3:
      case 4:
        reader.skip(4);
        cp[index] = { tag };
        break;
      case 5:
      case 6:
        if (index + 1 >= cpCount) reject('malformed-class', `entry=${entryName}; wide constant has no reserved slot`);
        reader.skip(8);
        cp[index] = { tag };
        index += 1;
        break;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        cp[index] = { tag, index: reader.u2() };
        break;
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        cp[index] = { tag, first: reader.u2(), second: reader.u2() };
        break;
      case 15:
        cp[index] = { tag, kind: reader.u1(), index: reader.u2() };
        break;
      default:
        reject('malformed-class', `entry=${entryName}; unknown constant-pool tag=${tag}`);
    }
  }

  const utf8 = (index) => {
    const value = cp[index];
    if (!value || value.tag !== 1) reject('malformed-class', `entry=${entryName}; bad UTF8 index=${index}`);
    return value.value;
  };
  const className = (index) => {
    const value = cp[index];
    if (!value || value.tag !== 7) reject('malformed-class', `entry=${entryName}; bad class index=${index}`);
    return utf8(value.index);
  };
  const nameAndType = (index) => {
    const value = cp[index];
    if (!value || value.tag !== 12) reject('malformed-class', `entry=${entryName}; bad name/type index=${index}`);
    return { name: utf8(value.first), descriptor: utf8(value.second) };
  };

  const classes = new Set();
  const members = [];
  for (let index = 1; index < cpCount; index += 1) {
    const value = cp[index];
    if (!value) continue;
    if (value.tag === 7) {
      const referencedClass = className(index);
      if (referencedClass.startsWith('[')) addDescriptorClasses(referencedClass, classes);
      else classes.add(referencedClass);
    }
    if (value.tag === 9 || value.tag === 10 || value.tag === 11) {
      const owner = className(value.first);
      const { name, descriptor } = nameAndType(value.second);
      addDescriptorClasses(descriptor, classes);
      members.push({ owner, name, descriptor, kind: value.tag === 9 ? 'field' : 'method' });
    } else if (value.tag === 16) {
      addDescriptorClasses(utf8(value.index), classes);
    } else if (value.tag === 12) {
      addDescriptorClasses(utf8(value.second), classes);
    }
  }

  validateConstantPool(cp, utf8, className, nameAndType, entryName);
  const classAccess = reader.u2();
  const thisClass = className(reader.u2());
  const superClass = reader.u2();
  if (superClass !== 0) className(superClass);
  const interfaceCount = reader.u2();
  if (interfaceCount > cpCount) reject('malformed-class', `entry=${entryName}; interface count=${interfaceCount}`);
  for (let i = 0; i < interfaceCount; i += 1) className(reader.u2());
  const fields = skipMembers(reader, cp, utf8, entryName, classes);
  const methods = skipMembers(reader, cp, utf8, entryName, classes);
  skipAttributes(reader, cp, utf8, entryName);
  if (reader.offset !== bytes.length) reject('malformed-class', `entry=${entryName}; trailing class bytes=${bytes.length - reader.offset}`);
  return {
    classes,
    members,
    constants: cp.filter((value) => value?.tag === 1).map((value) => value.value),
    fields,
    methods,
    classAccess,
    thisClass,
  };
}

export function decodeModifiedUtf8(bytes, entryName = '<class>', constantIndex = 0) {
  const context = `entry=${entryName}; CONSTANT_Utf8 index=${constantIndex}`;
  if (!Buffer.isBuffer(bytes) || bytes.length > MAX_CONSTANT_UTF8_BYTES) {
    reject('malformed-class', `${context}; modified UTF-8 length must be 0..${MAX_CONSTANT_UTF8_BYTES}`);
  }

  const units = new Uint16Array(bytes.length);
  let unitCount = 0;
  const fail = (reason, offset) => reject('malformed-class', `${context}; modified UTF-8 ${reason} at byte=${offset}`);
  const continuation = (offset, sequenceStart) => {
    if (offset >= bytes.length) fail('truncated sequence', sequenceStart);
    if ((bytes[offset] & 0xc0) !== 0x80) fail('bad continuation', offset);
    return bytes[offset];
  };

  for (let offset = 0; offset < bytes.length;) {
    const first = bytes[offset];
    if (first === 0) fail('forbidden raw NUL', offset);
    if (first <= 0x7f) {
      units[unitCount++] = first;
      offset += 1;
      continue;
    }

    if (first === 0xc0 || first === 0xc1) {
      const second = continuation(offset + 1, offset);
      if (first === 0xc0 && second === 0x80) units[unitCount++] = 0;
      else fail('disallowed overlong sequence', offset);
      offset += 2;
      continue;
    }

    if (first >= 0xc2 && first <= 0xdf) {
      const second = continuation(offset + 1, offset);
      units[unitCount++] = ((first & 0x1f) << 6) | (second & 0x3f);
      offset += 2;
      continue;
    }

    if (first >= 0xe0 && first <= 0xef) {
      const second = continuation(offset + 1, offset);
      if (first === 0xe0 && second < 0xa0) fail('disallowed overlong sequence', offset);
      const third = continuation(offset + 2, offset);
      // Modified UTF-8 encodes UTF-16 code units, so surrogate code units are
      // valid here and an astral character arrives as a three-byte pair.
      units[unitCount++] = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
      offset += 3;
      continue;
    }

    if (first >= 0xf0 && first <= 0xf7) fail('forbidden four-byte sequence', offset);
    fail(`invalid lead byte=0x${first.toString(16).padStart(2, '0')}`, offset);
  }

  const chunks = [];
  for (let start = 0; start < unitCount; start += 4096) {
    chunks.push(String.fromCharCode(...units.subarray(start, Math.min(start + 4096, unitCount))));
  }
  return chunks.join('');
}

function inspectPaths(entries) {
  for (const name of entries.keys()) {
    const lower = name.toLowerCase();
    if (lower.startsWith('assets/') || lower.startsWith('data/')) reject('custom-content-entry', `entry=${name}`);
    if (lower.endsWith('.jar')) reject('nested-jar', `entry=${name}`);
    if (lower.endsWith('.accesswidener')) reject('mixin-or-access-widener', `entry=${name}`);
    if (lower.endsWith('.mixins.json') && name !== SERVER_OUTPUT_MIXIN_CONFIG) {
      reject('mixin-or-access-widener', `entry=${name}`);
    }
    if (lower === 'pack.mcmeta' || lower.endsWith('/pack.mcmeta') || lower === 'pack.png' || lower.endsWith('/pack.png')) {
      reject('resource-pack-metadata', `entry=${name}`);
    }
  }
}

function inspectMetadata(entries) {
  const fabric = parseJson(entries.get('fabric.mod.json'), 'fabric.mod.json');
  const mixinConfig = parseJson(entries.get(SERVER_OUTPUT_MIXIN_CONFIG), SERVER_OUTPUT_MIXIN_CONFIG);
  const refmap = parseJson(entries.get('worldspeaker-refmap.json'), 'worldspeaker-refmap.json');
  const embeddedBytes = entries.get('worldspeaker-deployment.json');
  const embedded = parseJson(embeddedBytes, 'worldspeaker-deployment.json');
  const canonicalBytes = readFileSync(resolve(REPOSITORY_ROOT, 'compatibility/deployment.json'));
  const canonical = parseJson(canonicalBytes, 'compatibility/deployment.json');
  const props = parseProperties(entries.get('worldspeaker-deployment.properties'), 'worldspeaker-deployment.properties');
  const expectedHash = entries.get('worldspeaker-deployment.sha256').toString('utf8').trim();
  const canonicalHash = createHash('sha256').update(canonicalBytes).digest('hex');
  if (!/^[a-f0-9]{64}$/.test(expectedHash) || expectedHash !== canonicalHash) {
    reject('embedded-contract-hash-mismatch', `entry=worldspeaker-deployment.sha256; observed=${expectedHash}; expected=${canonicalHash}`);
  }
  if (stableJson(embedded) !== stableJson(canonical)) {
    reject('embedded-contract-drift', 'entry=worldspeaker-deployment.json; content differs from canonical deployment contract');
  }
  if (fabric.id !== 'worldspeaker') reject('metadata-drift', `entry=fabric.mod.json; field=id; observed=${String(fabric.id)}`);
  if (fabric.environment !== 'server') reject('metadata-client-environment', `entry=fabric.mod.json; field=environment; observed=${String(fabric.environment)}`);
  if (!exactObjectKeys(fabric.entrypoints, ['main']) || !Array.isArray(fabric.entrypoints.main) ||
      fabric.entrypoints.main.length !== 1 || fabric.entrypoints.main[0] !== MAIN_ENTRYPOINT) {
    reject('metadata-entrypoint-drift', `entry=fabric.mod.json; field=entrypoints; expected=main:${MAIN_ENTRYPOINT}`);
  }
  if (stableJson(fabric.mixins) !== stableJson([SERVER_OUTPUT_MIXIN_CONFIG]) ||
       stableJson(mixinConfig) !== stableJson({
        required: true,
        minVersion: '0.8.5',
        package: 'org.worldspeaker.mixin',
        compatibilityLevel: 'JAVA_21',
        refmap: 'worldspeaker-refmap.json',
         mixins: REVIEWED_MIXIN_NAMES,
       }) || stableJson(refmap) !== stableJson(EXPECTED_MIXIN_REFMAP) ||
       Object.hasOwn(fabric, 'accessWidener') || Object.hasOwn(fabric, 'access_widener')) {
    reject('metadata-mixin-boundary-invalid',
      `entry=fabric.mod.json; expected only server accessor config=${SERVER_OUTPUT_MIXIN_CONFIG}`);
  }
  if (!embedded.target || embedded.target.serverOnly !== true || embedded.target.selection !== 'selected' ||
      embedded.mod?.id !== 'worldspeaker' || embedded.qualification?.state !== 'unqualified') {
    reject('embedded-server-only-contract-invalid', 'entry=worldspeaker-deployment.json; expected selected server-only unqualified Worldspeaker contract');
  }
  if (fabric.depends?.minecraft !== embedded.target.minecraft ||
      fabric.depends?.fabricloader !== embedded.runtimeDependencies?.fabricLoader ||
      fabric.depends?.['fabric-api'] !== embedded.runtimeDependencies?.fabricApi ||
      fabric.depends?.java !== '>=21') {
    reject('metadata-dependency-drift', 'entry=fabric.mod.json; dependencies disagree with embedded deployment contract');
  }
  if (props.deploymentId !== embedded.deploymentId || props.manifestRevision !== embedded.manifestRevision ||
      props.manifestSha256 !== expectedHash || props.minecraftVersion !== embedded.target.minecraft ||
      props.fabricLoaderVersion !== embedded.runtimeDependencies.fabricLoader ||
      props.fabricApiVersion !== embedded.runtimeDependencies.fabricApi ||
      props.javaMajor !== String(embedded.target.java.major)) {
    reject('embedded-properties-drift', 'entry=worldspeaker-deployment.properties; values disagree with embedded JSON contract');
  }
}

function inspectApiReferences(refs) {
  for (const ref of refs.members) {
    if (!isGameApi(ref.owner)) continue;
    const mapped = mappedReference(ref);
    const forbidden = classifyForbidden(mapped.owner, mapped.name);
    const symbol = `${ref.owner}#${ref.name}${ref.descriptor}`;
    const mappedSymbol = `${mapped.owner}#${mapped.name}${mapped.descriptor}`;
    if (forbidden) reject(forbidden, `entry=${ref.entry}; reference=${symbol}; mapped=${mappedSymbol}`);
    if (!isAllowedMember(ref)) reject('unknown-game-api-reference', `entry=${ref.entry}; reference=${symbol}`);
  }
  for (const { name, entry } of refs.classReferences) {
    if (name.startsWith('org/spongepowered/asm/mixin/')) {
      const accessorOnly = name === 'org/spongepowered/asm/mixin/gen/Accessor';
      const commandCaptureOnly = name === 'org/spongepowered/asm/mixin/injection/ModifyArgs' ||
        name === 'org/spongepowered/asm/mixin/injection/invoke/arg/Args';
      if (!REVIEWED_MIXIN_ENTRIES.includes(entry) || !ALLOWED_MIXIN_API_CLASSES.has(name) ||
          accessorOnly && entry !== SERVER_OUTPUT_MIXIN_ENTRY ||
          commandCaptureOnly && entry !== 'org/worldspeaker/mixin/ServerPlayNetworkHandlerInvocationMixin.class') {
        reject('mixin-api-reference', `entry=${entry}; reference=${name}`);
      }
      continue;
    }
    const mappedName = canonicalClass(name);
    if (isClientApi(name) || isClientApi(mappedName)) reject('client-api-reference', `reference=${name}`);
    const forbidden = classifyForbiddenClass(mappedName);
    if (forbidden) reject(forbidden, `reference=${name}; mapped=${mappedName}`);
    if (isGameApi(name) && !isAllowedClass(name)) reject('unknown-game-api-reference', `reference=${name}`);
  }
}

function inspectCommandCaptureHandlers(parsed, entry) {
  const handlers = [
    ['worldspeaker$bindUnsignedCommandReceipt', 'CommandExecutionC2SPacket'],
    ['worldspeaker$bindSignedCommandReceipt', 'ChatCommandSignedC2SPacket'],
  ];
  for (const [name, packet] of handlers) {
    const methods = parsed.methods.filter((method) => method.name === name);
    const descriptor = '(Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;' +
      `Lnet/minecraft/network/packet/c2s/play/${packet};)V`;
    if (methods.length !== 1 || methods[0].access !== 0x0002 ||
        mapDescriptorClasses(methods[0].descriptor) !== descriptor ||
        !parsed.constants.includes('Lorg/spongepowered/asm/mixin/injection/ModifyArgs;') ||
        parsed.constants.includes('Lorg/spongepowered/asm/mixin/injection/ModifyArg;')) {
      reject('command-capture-mixin-bytecode-invalid', `entry=${entry}; handler=${name}; expected enclosing packet capture with @ModifyArgs`);
    }
  }
}

function inspectServerOutputAccessorClass(parsed, entry) {
  const annotationTypes = parsed.constants.filter((value) => value.startsWith('Lorg/spongepowered/asm/mixin/'))
    .sort();
  const expectedAnnotationTypes = [
    'Lorg/spongepowered/asm/mixin/Mixin;',
    'Lorg/spongepowered/asm/mixin/gen/Accessor;',
  ].sort();
  const expectedMethod = parsed.methods.length === 1 && parsed.methods[0].access === 0x0401 &&
    parsed.methods[0].name === 'worldspeaker$getCommandOutput' &&
    mapDescriptorClasses(parsed.methods[0].descriptor) === '()Lnet/minecraft/server/command/CommandOutput;';
  const expectedClassReferences = new Set([
    'java/lang/Object',
    'net/minecraft/server/command/CommandOutput',
    'net/minecraft/server/command/ServerCommandSource',
  ]);
  const annotationClassReferences = new Set();
  for (const constant of parsed.constants) addDescriptorClasses(constant, annotationClassReferences);
  const actualGameReferences = [...new Set([...parsed.classes, ...annotationClassReferences])]
    .filter((name) => canonicalClass(name).startsWith('net/minecraft/'))
    .map(canonicalClass);
  if (parsed.thisClass !== SERVER_OUTPUT_MIXIN_CLASS || (parsed.classAccess & 0x0200) === 0 ||
      parsed.fields.length !== 0 || !expectedMethod || !parsed.constants.includes('output') ||
      stableJson(annotationTypes) !== stableJson(expectedAnnotationTypes) ||
      actualGameReferences.some((name) => !expectedClassReferences.has(name)) ||
      [...expectedClassReferences].some((name) => name.startsWith('net/minecraft/') && !actualGameReferences.includes(name))) {
    reject('mixin-accessor-bytecode-invalid', `entry=${entry}; expected only ServerCommandSource.output accessor`);
  }
}

function canonicalClass(name) {
  return CLASS_INTERMEDIARY_TO_NAMED.get(name) ?? name;
}

function canonicalMember(ref) {
  const mapped = mappedReference(ref);
  return `${mapped.owner}#${mapped.name}${mapped.descriptor}`;
}

function mappedReference(ref) {
  return {
    owner: canonicalClass(ref.owner),
    name: MEMBER_INTERMEDIARY_TO_NAMED.get(`${ref.owner}#${ref.name}${ref.descriptor}`) ?? ref.name,
    descriptor: mapDescriptorClasses(ref.descriptor),
  };
}

function isAllowedClass(name) {
  return ALLOWED_API_CLASSES.has(name) || ALLOWED_API_CLASSES.has(canonicalClass(name));
}

function isAllowedMember(ref) {
  return ALLOWED_API_MEMBERS.has(`${ref.owner}#${ref.name}${ref.descriptor}`) ||
    ALLOWED_API_MEMBERS.has(canonicalMember(ref));
}

function isGameApi(name) {
  return name.startsWith('net/minecraft/') || name.startsWith('net/fabricmc/') ||
    name.startsWith('com/mojang/brigadier/') || name.startsWith('com/mojang/authlib/');
}

function isClientApi(name) {
  return name.startsWith('net/minecraft/client/') || name.startsWith('net/fabricmc/fabric/api/client/') ||
    name === 'net/fabricmc/api/ClientModInitializer' || name.startsWith('org/worldspeaker/client/');
}

function classifyForbidden(owner, member) {
  const symbol = `${owner}#${member}`;
  if (/CustomPayload|PayloadTypeRegistry|ServerPlayNetworking/.test(owner) && /register|send|receiver/i.test(member)) return 'custom-payload-api-reference';
  if (/ResourcePack|ServerPack|PackTask/.test(owner) || /ResourcePack/i.test(member)) return 'resource-pack-api-reference';
  if (isSignedPlayerChatApi(owner) && !isAllowedSignedChatRead(owner, member)) return 'signed-player-chat-api-reference';
  if (/net\/minecraft\/registry\/(Registry|Registries)$/.test(owner) && /register/i.test(member)) return 'custom-registry-api-reference';
  if (/fabricmc\/fabric\/api\/object\/builder\/v1\/.*Registry/.test(owner) && /register|build/i.test(member)) return 'custom-registry-api-reference';
  if (symbol.includes('net/minecraft/unused/')) return 'unknown-game-api-reference';
  return null;
}

function classifyForbiddenClass(name) {
  if (!isGameApi(name)) return null;
  if (name.startsWith('org/spongepowered/asm/mixin/')) return 'mixin-api-reference';
  if (/CustomPayload|PayloadTypeRegistry|ServerPlayNetworking/.test(name)) return 'custom-payload-api-reference';
  if (/ResourcePack|ServerPack|PackTask/.test(name)) return 'resource-pack-api-reference';
  if (isSignedPlayerChatApi(name) && !isAllowedSignedChatClass(name)) return 'signed-player-chat-api-reference';
  return null;
}

function isSignedPlayerChatApi(name) {
  return /SignedMessage|MessageSignature|PlayerChatMessage|ChatMessageC2SPacket|CommandExecutionC2SPacket|ChatCommandSignedC2SPacket|MessageType|FilterMask/.test(name);
}

function isAllowedSignedChatClass(name) {
  const canonical = canonicalClass(name);
  return [
    'net/minecraft/network/message/FilterMask',
    'net/minecraft/network/message/MessageType',
    'net/minecraft/network/message/MessageType$Parameters',
    'net/minecraft/network/message/SignedMessage',
    'net/minecraft/network/packet/c2s/play/ChatMessageC2SPacket',
    'net/minecraft/network/packet/c2s/play/CommandExecutionC2SPacket',
    'net/minecraft/network/packet/c2s/play/ChatCommandSignedC2SPacket',
  ].includes(canonical);
}

function isAllowedSignedChatRead(owner, member) {
  const pair = `${canonicalClass(owner)}#${MEMBER_INTERMEDIARY_TO_NAMED.get(`${owner}#${member}`) ?? member}`;
  return [
    'net/minecraft/network/message/FilterMask#isPassThrough',
    'net/minecraft/network/message/MessageType#CHAT',
    'net/minecraft/network/message/MessageType$Parameters#type',
    'net/minecraft/network/message/SignedMessage#filterMask',
    'net/minecraft/network/message/SignedMessage#getSender',
    'net/minecraft/network/message/SignedMessage#getSignedContent',
    'net/minecraft/network/message/SignedMessage#hasSignature',
  ].includes(pair);
}

function addDescriptorClasses(descriptor, target) {
  const pattern = /L([^;<:]+)(?:<[^;]*>)?;/g;
  for (const match of descriptor.matchAll(pattern)) target.add(match[1]);
}

function mapDescriptorClasses(descriptor) {
  return descriptor.replace(/L([^;<:]+)(?=[;<])/g, (match, name) => `L${canonicalClass(name)}`);
}

function validateConstantPool(cp, utf8, className, nameAndType, entryName) {
  for (let index = 1; index < cp.length; index += 1) {
    const value = cp[index];
    if (!value) continue;
    if (value.tag === 7 || value.tag === 8 || value.tag === 16 || value.tag === 19 || value.tag === 20) utf8(value.index);
    if (value.tag === 9 || value.tag === 10 || value.tag === 11) {
      className(value.first);
      nameAndType(value.second);
    }
    if (value.tag === 12) {
      utf8(value.first);
      utf8(value.second);
    }
    if (value.tag === 15) {
      const reference = cp[value.index];
      const expectedTags = value.kind >= 1 && value.kind <= 4 ? [9] :
        value.kind === 5 || value.kind === 8 ? [10] :
          value.kind === 6 || value.kind === 7 ? [10, 11] :
            value.kind === 9 ? [11] : [];
      if (!reference || !expectedTags.includes(reference.tag)) {
        reject('malformed-class', `entry=${entryName}; invalid method handle`);
      }
    }
    if (value.tag === 17 || value.tag === 18) nameAndType(value.second);
  }
}

function skipMembers(reader, cp, utf8, entryName, classes) {
  const count = reader.u2();
  if (count > 8192) reject('class-member-count-limit', `entry=${entryName}; count=${count}`);
  const members = [];
  for (let i = 0; i < count; i += 1) {
    const access = reader.u2();
    const name = utf8(reader.u2());
    const descriptor = utf8(reader.u2());
    addDescriptorClasses(descriptor, classes);
    skipAttributes(reader, cp, utf8, entryName);
    members.push({ access, name, descriptor });
  }
  return members;
}

function skipAttributes(reader, cp, utf8, entryName) {
  const count = reader.u2();
  if (count > 8192) reject('class-attribute-count-limit', `entry=${entryName}; count=${count}`);
  for (let i = 0; i < count; i += 1) {
    utf8(reader.u2());
    const length = reader.u4();
    reader.skip(length);
  }
}

class ClassReader {
  constructor(bytes, entryName) {
    this.buffer = bytes;
    this.entryName = entryName;
    this.offset = 0;
  }
  u1() { this.require(1); return this.buffer[this.offset++]; }
  u2() { this.require(2); const value = this.buffer.readUInt16BE(this.offset); this.offset += 2; return value; }
  u4() { this.require(4); const value = this.buffer.readUInt32BE(this.offset); this.offset += 4; return value; }
  bytes(length) { this.require(length); const value = this.buffer.subarray(this.offset, this.offset + length); this.offset += length; return value; }
  skip(length) { this.require(length); this.offset += length; }
  require(length) {
    if (!Number.isSafeInteger(length) || length < 0 || this.offset + length > this.buffer.length) {
      reject('malformed-class', `entry=${this.entryName}; truncated at byte=${this.offset}`);
    }
  }
}

function findEndRecord(bytes) {
  const minimum = Math.max(0, bytes.length - 65_557);
  for (let offset = bytes.length - 22; offset >= minimum; offset -= 1) {
    if (bytes.readUInt32LE(offset) !== 0x06054b50) continue;
    const commentLength = bytes.readUInt16LE(offset + 20);
    if (offset + 22 + commentLength !== bytes.length) continue;
    return {
      offset,
      diskNumber: bytes.readUInt16LE(offset + 4),
      centralDisk: bytes.readUInt16LE(offset + 6),
      diskEntries: bytes.readUInt16LE(offset + 8),
      entryCount: bytes.readUInt16LE(offset + 10),
      centralSize: bytes.readUInt32LE(offset + 12),
      centralOffset: bytes.readUInt32LE(offset + 16),
    };
  }
  reject('invalid-zip', 'end-of-central-directory record not found');
}

function readLocalEntry(archive, offset, name, centralFlags, centralMethod, compressedSize, uncompressedSize, crc, centralOffset) {
  requireRange(archive, offset, 30, 'local-entry-truncated');
  if (archive.readUInt32LE(offset) !== 0x04034b50) reject('invalid-local-entry', `entry=${name}`);
  const flags = archive.readUInt16LE(offset + 6);
  const method = archive.readUInt16LE(offset + 8);
  const localCrc = archive.readUInt32LE(offset + 14);
  const localCompressedSize = archive.readUInt32LE(offset + 18);
  const localUncompressedSize = archive.readUInt32LE(offset + 22);
  const nameLength = archive.readUInt16LE(offset + 26);
  const extraLength = archive.readUInt16LE(offset + 28);
  const localNameOffset = offset + 30;
  requireRange(archive, localNameOffset, nameLength + extraLength, 'local-entry-truncated');
  const localName = decodeEntryName(archive.subarray(localNameOffset, localNameOffset + nameLength), flags, name);
  if (localName !== name || flags !== centralFlags || method !== centralMethod) reject('local-central-entry-mismatch', `entry=${name}`);
  if (!(flags & 8) && (localCrc !== crc || localCompressedSize !== compressedSize || localUncompressedSize !== uncompressedSize)) {
    reject('local-central-entry-mismatch', `entry=${name}; size/crc`);
  }
  const localExtra = archive.subarray(localNameOffset + nameLength, localNameOffset + nameLength + extraLength);
  rejectZip64Extra(localExtra, name);
  const dataOffset = localNameOffset + nameLength + extraLength;
  if (dataOffset + compressedSize > centralOffset) reject('local-entry-out-of-bounds', `entry=${name}`);
  let descriptorLength = 0;
  if (flags & 8) {
    let descriptor = dataOffset + compressedSize;
    requireRange(archive, descriptor, 12, 'data-descriptor-truncated');
    if (archive.readUInt32LE(descriptor) === 0x08074b50) {
      descriptor += 4;
      descriptorLength = 16;
    } else {
      descriptorLength = 12;
    }
    requireRange(archive, descriptor, 12, 'data-descriptor-truncated');
    if (archive.readUInt32LE(descriptor) !== crc || archive.readUInt32LE(descriptor + 4) !== compressedSize ||
        archive.readUInt32LE(descriptor + 8) !== uncompressedSize) reject('invalid-data-descriptor', `entry=${name}`);
    if (dataOffset + compressedSize + descriptorLength > centralOffset) reject('local-entry-out-of-bounds', `entry=${name}`);
  }
  return { dataOffset, descriptorLength };
}

function rejectZip64Extra(extra, name) {
  let offset = 0;
  while (offset < extra.length) {
    if (offset + 4 > extra.length) reject('malformed-zip-extra', `entry=${name}`);
    const id = extra.readUInt16LE(offset);
    const length = extra.readUInt16LE(offset + 2);
    offset += 4;
    if (offset + length > extra.length) reject('malformed-zip-extra', `entry=${name}`);
    if (id === 1) reject('zip64-not-supported', `entry=${name}`);
    offset += length;
  }
}

function decodeEntryName(raw, flags, label) {
  if (!(flags & 0x800) && raw.some((byte) => byte > 0x7f)) reject('non-ascii-entry-name', `entry=${label}`);
  try { return new TextDecoder('utf-8', { fatal: true }).decode(raw); }
  catch { reject('invalid-entry-name', `entry=${label}`); }
}

function validateEntryName(name) {
  if (!name || name.startsWith('/') || name.includes('\\') || name.split('/').some((part) => part === '..' || part === '.')) {
    reject('unsafe-entry-name', `entry=${name}`);
  }
}

function parseJson(bytes, name) {
  if (!bytes || bytes.length > 256 * 1024) reject('metadata-size-limit', `entry=${name}`);
  try { return JSON.parse(bytes.toString('utf8')); }
  catch { reject('invalid-metadata', `entry=${name}`); }
}

function parseProperties(bytes, name) {
  if (!bytes || bytes.length > 64 * 1024) reject('metadata-size-limit', `entry=${name}`);
  const values = {};
  for (const line of bytes.toString('utf8').split(/\r?\n/)) {
    if (!line || line.startsWith('#') || line.startsWith('!')) continue;
    const separator = line.indexOf('=');
    if (separator <= 0) reject('invalid-metadata', `entry=${name}; malformed property`);
    values[line.slice(0, separator)] = line.slice(separator + 1);
  }
  return values;
}

function exactObjectKeys(object, keys) {
  return object != null && typeof object === 'object' && !Array.isArray(object) &&
    Object.keys(object).sort().join('\0') === [...keys].sort().join('\0');
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value != null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function requireRange(bytes, offset, length, code) {
  if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length < 0 || offset + length > bytes.length) {
    reject(code, `offset=${offset}; length=${length}`);
  }
}

function checkDeadline(startedAt) {
  const elapsed = performance.now() - startedAt;
  if (elapsed > MAX_VERIFY_MILLIS) reject('time-limit', `elapsed>${MAX_VERIFY_MILLIS}ms`);
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function makeCrcTable() {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let value = n;
    for (let bit = 0; bit < 8; bit += 1) value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
    table[n] = value >>> 0;
  }
  return table;
}

function safeReason(error) {
  return String(error?.code ?? error?.message ?? 'invalid compressed data').replace(/[\r\n\t]/g, ' ').slice(0, 160);
}

function reject(code, detail) {
  const error = new Error(`WS-SERVER-ONLY-REJECTED: ${code}: ${detail}`);
  error.code = code;
  throw error;
}

async function cli(argv) {
  if (argv.length !== 2 || argv[0] !== '--jar' || !argv[1]) {
    throw new Error('Usage: node tools/compat/verify-server-only.mjs --jar <artifact.jar>');
  }
  const result = await verifyJarWithWallClockLimit(argv[1]);
  process.stdout.write(`WS-SERVER-ONLY-PASS: sha256=${result.sha256} bytes=${result.bytes} entries=${result.entries} classes=${result.classes}\n`);
}

function verifyJarWithWallClockLimit(jarPath) {
  return new Promise((resolveResult, rejectResult) => {
    const worker = new Worker(new URL(import.meta.url), {
      workerData: { verifyServerOnlyWorker: true, jarPath },
    });
    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      void worker.terminate();
      rejectResult(new Error(`WS-SERVER-ONLY-REJECTED: time-limit: exceeded ${MAX_VERIFY_MILLIS}ms wall-clock budget`));
    }, MAX_VERIFY_MILLIS);
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      callback(value);
    };
    worker.once('message', (message) => {
      if (message?.ok) finish(resolveResult, message.result);
      else finish(rejectResult, new Error(message?.error ?? 'WS-SERVER-ONLY-REJECTED: worker-failed: no result'));
    });
    worker.once('error', (error) => finish(rejectResult, error));
    worker.once('exit', (code) => {
      if (code !== 0) finish(rejectResult, new Error(`WS-SERVER-ONLY-REJECTED: worker-exit: code=${code}`));
    });
  });
}

if (!isMainThread && workerData?.verifyServerOnlyWorker === true) {
  try {
    parentPort.postMessage({ ok: true, result: verifyJar(workerData.jarPath) });
  } catch (error) {
    parentPort.postMessage({ ok: false, error: error.message });
  } finally {
    parentPort.close();
  }
} else if (isMainThread && process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  cli(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
