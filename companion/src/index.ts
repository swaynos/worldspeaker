import { defineChannelPluginEntry } from 'openclaw/plugin-sdk/channel-core';
import { setAcknowledgementContextUpdater, worldspeakerChannel } from './channel.js';
import { createAcknowledgementContextUpdater } from './context-update.js';

export { ABILITY_SCHEMA_PREDICATE_VOCABULARY, installAbilitySchemaPredicates } from './ability-catalogue.js';

const entry: ReturnType<typeof defineChannelPluginEntry> = defineChannelPluginEntry({
  id: 'worldspeaker',
  name: 'Worldspeaker',
  description: 'Authenticated Minecraft server conversation channel.',
  plugin: worldspeakerChannel,
  registerFull(api) {
    const enqueueNextTurnInjection = (input: Parameters<typeof api.session.workflow.enqueueNextTurnInjection>[0]) =>
      api.session.workflow.enqueueNextTurnInjection(input);
    // The channel handler uses the SDK-owned session queue; no transcript files or internal imports.
    setAcknowledgementContextUpdater(createAcknowledgementContextUpdater(enqueueNextTurnInjection));
  },
});

export default entry;
