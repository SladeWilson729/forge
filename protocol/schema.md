# Forge Arena protocol catalog and schema

Generated against Forge 2.0.16-SNAPSHOT at commit f3ca67d17d

This is the v1 draft from project plan v10, sections 2.1, 2.2, 2.5, 2.6, 3 (including decisions in 3.5) and 7.8. It documents a future bridge; it does not describe the Phase 0 opening-hand WebSocket payload. Section E records the plan 3.5 decisions and defaults, with the tasks that finish each default. The schema types source-supported values and leaves explicitly unresolved payloads open; passing schema validation alone is not authorization or a guarantee of per-seat visibility.

Sources are repository-relative to this commit. The enum signatures below preserve erased class types from [ProtocolMethod](../forge-gui/src/main/java/forge/gamemodes/net/ProtocolMethod.java); generic meanings are described in mappings and section D. [ProtocolGuiGame](../forge-gui/src/main/java/forge/gamemodes/net/ProtocolGuiGame.java), [IGuiGame](../forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java), [IGameController](../forge-gui/src/main/java/forge/interfaces/IGameController.java), [IMacroSystem](../forge-gui/src/main/java/forge/interfaces/IMacroSystem.java), [NetworkGuiGame](../forge-gui/src/main/java/forge/gamemodes/net/NetworkGuiGame.java), and [AbstractGuiGame](../forge-gui/src/main/java/forge/gamemodes/match/AbstractGuiGame.java) are the implementation sources. [ProtocolGuiGameInProcessTest](../forge-gui-desktop/src/test/java/forge/net/embedded/ProtocolGuiGameInProcessTest.java) verifies an embedded IRemote receives full views, state deltas, raw events and blocking calls while actions run on the EDT; its script is not an exhaustive prompt contract.

## A. ProtocolMethod catalog

59 entries: **40 SERVER, 16 CLIENT, 3 MACRO**, in enum order. Blocking means waiting for a protocol reply, not graph synchronization or the Input latch. `sync*` walks the graph; bare helpers do not.

| Method | Mode | Return type | Argument types (in order) | ProtocolGuiGame helper | Blocking | JSON mapping |
|---|---|---|---|---|---|---|
| `setGameView` | SERVER | void | GameView, long | send | no | state: full per-seat snapshot; Forge sequence is a baseline, Arena seq is per seat |
| `openView` | SERVER | void | TrackableCollection | send | no | ignored: no separate open-view message; local-player association supplies seat context (E10) |
| `afterGameEnd` | SERVER | void | none | syncAndSend | no | event.kind=gameOver from outcome stream; state reflects final game; no distinct lifecycle message (E10) |
| `showCombat` | SERVER | void | none | syncAndSend | no | state.players[].zones.battlefield: attacking/blocking/attackTarget from refreshed combat |
| `showPromptMessage` | SERVER | void | PlayerView, String, CardView | send / syncAndSend | no | state.activeInput.prompt/sourceCardId (E7 decided task 1.5) |
| `updateDrawOffer` | SERVER | void | DrawOfferMessage.Status | send | no | ignored: decided out of v1 (plan 3.5 E12) |
| `updateButtons` | SERVER | void | PlayerView, String, String, boolean, boolean, boolean | send | no | state.activeInput.okLabel/cancelLabel/buttons.*.enabled; owner implicit in receiving seat; focus1 local UI hint |
| `flashIncorrectAction` | SERVER | void | none | send | no | message.level=rejected with generic text; Forge call has no reason text (plan 3.5 E3) |
| `alertUser` | SERVER | void | none | send | no | attention: type and seq only; taskbar flash when the action is on this seat (plan 3.5 E10) |
| `enableOverlay` | SERVER | void | none | send | no | ignored: no overlay field/message specified; decided local/ignored in plan 3.5 E10 |
| `disableOverlay` | SERVER | void | none | send | no | ignored: no overlay field/message specified; decided local/ignored in plan 3.5 E10 |
| `finishGame` | SERVER | void | none | syncAndSend | no | ignored: no distinct finish-view message; gameOver event is outcome-driven; decided local/ignored in plan 3.5 E10 |
| `showManaPool` | SERVER | void | PlayerView | send | no | state.activeInput.manaPoolOpen = true |
| `hideManaPool` | SERVER | void | PlayerView | send | no | state.activeInput.manaPoolOpen = false |
| `setPanelSelection` | SERVER | void | CardView | syncAndSend | no | ignored: no panel-focus field specified; decided local/ignored in plan 3.5 E10 |
| `getAbilityToPlay` | SERVER | SpellAbilityView | CardView, List, ITriggerEvent | syncAndSendAndWait | yes | prompt.kind=chooseAbility, sourceCardId=hostCard, options=[{optionId,label}]; promptReply.result=optionId |
| `assignCombatDamage` | SERVER | Map | CardView, List, int, GameEntityView, boolean, boolean | syncAndSendAndWait | yes | prompt.kind=assignCombatDamage; amount, lethal thresholds, order/skip flags and optional defenderOptionId; result allocations |
| `assignGenericAmount` | SERVER | Map | CardView, Map, int, boolean, String | syncAndSendAndWait | yes | prompt.kind=assignGeneric; amount, capacities, atLeastOne; result allocations |
| `message` | SERVER | void | String, String | send | no | message.level=info, text=message, optional title=title (plan 3.5 E3) |
| `showErrorDialog` | SERVER | void | String, String | send | no | message.level=error, text=message, optional title=title (plan 3.5 E3) |
| `showConfirmDialog` | SERVER | boolean | String, String, String, String, boolean | sendAndWait | yes | prompt.kind=confirm; title, text, option labels and default boolean |
| `showOptionDialog` | SERVER | int | String, String, FSkinProp, List, int | syncAndSendAndWait | yes | prompt.kind=option; title, text, option labels, default index; result integer index (-1 cancels) |
| `showInputDialog` | SERVER | String | String, String, FSkinProp, String, List, boolean | syncAndSendAndWait | yes | prompt.kind=number or text; title, text, initialInput, option labels; result String or null |
| `confirm` | SERVER | boolean | CardView, String, boolean, List | syncAndSendAndWait | yes | prompt.kind=confirm; sourceCardId, text, option labels, default boolean |
| `getChoices` | SERVER | List | String, int, int, List, List, FSerializableFunction | syncAndSendAndWait | yes | prompt.kind=choose; selected optionIds, server-rendered labels, min/max selected; -1/-1 becomes revealOnly with min=max=0 |
| `order` | SERVER | OrderResult | String, String, int, int, List, List, CardView, boolean, boolean | syncAndSendAndWait | yes | prompt.kind=order; source/destination optionIds, remainingMin/Max, min/max destination count, top, sourceCardId and flags; result {ordered:[optionIds],remember:boolean} |
| `sideboard` | SERVER | List | CardPool, CardPool, String | syncAndSendAndWait | yes | ignored on wire: server returns unchanged main.toFlatList() and logs (out of v1) |
| `chooseSingleEntityForEffect` | SERVER | GameEntityView | String, List, DelayedReveal, boolean | syncAndSendAndWait | yes | prompt.kind=chooseEntities; single=true, optional, options, min/max, revealed cards; result [optionId] converts to one GameEntityView or optional null |
| `chooseEntitiesForEffect` | SERVER | List | String, List, int, int, DelayedReveal | syncAndSendAndWait | yes | prompt.kind=chooseEntities; single=false, options, min/max, revealed cards; result [optionIds] converts to List<GameEntityView> |
| `manipulateCardList` | SERVER | List | String, Iterable, Iterable, boolean, boolean, boolean | syncAndSendAndWait | yes | prompt.kind=manipulateList; manipulable optionIds, toTop/toBottom/toAnywhere; result permutation of all optionIds |
| `setCard` | SERVER | void | CardView | syncAndSend | no | ignored: preview card has no named field/message; decided local/ignored in plan 3.5 E10 |
| `setSelection` | SERVER | void | Selection | syncAndSend | no | state.activeInput.chosen/min/max/targets/selectable (E7 decided task 1.5; mixed references use EntityRef) |
| `showRevealedCards` | SERVER | void | Iterable | syncAndSend | no | event.kind=reveal, cardIds=filtered cards; temporary visibility from preceding sync |
| `hideRevealedCards` | SERVER | void | none | send | no | ignored: plan has reveal but no clear-reveal message; decided local/ignored in plan 3.5 E10 |
| `setWeaklySelectable` | SERVER | void | Iterable | send (after updateGameView) | no | state.activeInput.highlighted[].id/strength (duplicates retain strength) |
| `clearWeaklySelectable` | SERVER | void | none | send (after updateGameView) | no | state.activeInput.highlighted=[] |
| `awaitNextInput` | SERVER | void | none | send | no | state.activeInput.kind=waiting (no reply wait) |
| `applyDelta` | SERVER | void | DeltaPacket | send (direct sender; also write for non-flushing path) | no | state when new/changed objects exist; event and log for events; events-only packets reuse last snapshot |
| `applyYieldUpdate` | SERVER | void | YieldUpdate | send | no | yieldState.kind and variant fields |
| `applyMacroState` | SERVER | void | IMacroSystem.State | send | no | macroState.recording/replaying/hasActions |
| `useMana` | CLIENT | void | byte | not sent by ProtocolGuiGame | no | useMana.color |
| `undoLastAction` | CLIENT | void | none | not sent by ProtocolGuiGame | no | undo |
| `selectPlayer` | CLIENT | void | PlayerView, ITriggerEvent | not sent by ProtocolGuiGame | no | selectPlayer.playerId; also composite defender selection |
| `selectCard` | CLIENT | void | CardView, List, ITriggerEvent | not sent by ProtocolGuiGame | no | selectCard.cardId/otherCardIds/button; also combat composites |
| `selectButtonOk` | CLIENT | void | none | not sent by ProtocolGuiGame | no | ok |
| `selectButtonCancel` | CLIENT | void | none | not sent by ProtocolGuiGame | no | cancel |
| `selectAbility` | CLIENT | void | SpellAbilityView | not sent by ProtocolGuiGame | no | selectAbility.abilityId (non-prompt Input path only) |
| `selectStackItem` | CLIENT | void | StackItemView | not sent by ProtocolGuiGame | no | selectStackItem.stackId |
| `nextGameDecision` | CLIENT | void | NextGameDecision | not sent by ProtocolGuiGame | no | nextGame.decision: rematch -> NextGameDecision.NEW, quit -> QUIT; CONTINUE unused (one game per match) |
| `getActivateDescription` | CLIENT | String | CardView | not sent by ProtocolGuiGame | yes | describeAbility.cardId -> abilityDescription.cardId/text; finished in task 1.12 area |
| `concede` | CLIENT | void | none | not sent by ProtocolGuiGame | no | concede |
| `drawOfferAction` | CLIENT | void | DrawOfferMessage.Action | not sent by ProtocolGuiGame | no | ignored: decided out of v1 (plan 3.5 E12) |
| `alphaStrike` | CLIENT | void | none | not sent by ProtocolGuiGame | no | attackAll.defenderId then alphaStrike |
| `reorderHand` | CLIENT | void | CardView, int | not sent by ProtocolGuiGame | no | reorderHand.cardId/index |
| `requestResync` | CLIENT | void | none | not sent by ProtocolGuiGame | no | ignored: PlayerControllerHuman implementation is a no-op; resync uses cache plus ProtocolGuiGame.setResyncPending() |
| `sendYieldUpdate` | CLIENT | void | YieldUpdate | not sent by ProtocolGuiGame | no | setYield.kind and variant fields |
| `setRememberedActions` | MACRO | void | none | not sent by ProtocolGuiGame | no | macroRecord |
| `nextRememberedAction` | MACRO | void | none | not sent by ProtocolGuiGame | no | macroNext |
| `repeatRememberedActions` | MACRO | void | none | not sent by ProtocolGuiGame | no | macroRepeat.count makes N parameterless calls, stopping if a prompt opens |

`setGameView` sends through updateGameView/sendFullState, not a one-to-one forwarding override. `applyDelta` uses GameProtocolSender directly after collection or for events-only replay; `write` batches without flushing. `showPromptMessage` uses send for a null card (timer path), otherwise syncAndSend. `getAbilityToPlay` deliberately sends a null triggerEvent. `getActivateDescription` is blocking in NetGameController.sendAndWait although it is CLIENT mode. The enum declares selectCard void while IGameController returns boolean: the native network drops that return. Section C covers the public controller methods separately.

## B. IGuiGame methods without a ProtocolMethod entry

**53 distinct method names (57 overload signatures)**. Rows group overloads of the same name. Convenience overloads of names already in A (setGameView, showPromptMessage, updateButtons, message, showErrorDialog, showConfirmDialog, confirm, getChoices and order) delegate to the cataloged signature; they are not additional absent names. NetworkGuiGame inherits the behavior below except for its native applyDelta receiver, which is already in A.

| Method | Implementation on ProtocolGuiGame | Mapping or local reason |
|---|---|---|
| `isLibgdxPort` | IGuiGame default asks GuiBase.getInterface().isLibgdxPort(). | local only: renderer capability, no v1 negotiation field (E10) |
| `getGameView` | AbstractGuiGame returns its stored GameView. | local only: host view access; snapshots are triggered by section A |
| `setOriginalGameController` | AbstractGuiGame registers original/current controller, initializes current player. | local only: seat/controller bookkeeping |
| `setGameController` | AbstractGuiGame updates controlled-player map, restores original controller or removes mapping on null. | local only: control bookkeeping; mapped players reflected in state |
| `setSpectator` | AbstractGuiGame stores spectator controller. | local only: spectator controller reference |
| `showCoinFlip` | Inherited IGuiGame default no-op. | local only: renderer animation hook; coinFlip event uses GameEventFlipCoin instead; flip animation coverage E10 |
| `showFlipOntoBattlefield` | Inherited IGuiGame default no-op. | local only: renderer animation hook; coinFlip event uses GameEventFlipCoin instead; flip animation coverage E10 |
| `endFlipOntoBattlefield` | Inherited IGuiGame default no-op. | local only: renderer animation hook; coinFlip event uses GameEventFlipCoin instead; flip animation coverage E10 |
| `updatePhase` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateTurn` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updatePlayerControl` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateStack` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `onSnapshotRestored` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `notifyStackAddition` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `notifyStackRemoval` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `handleLandPlayed` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `handleGameEvent` | ProtocolGuiGame delegates to handleGameEvents(List.of(event)). | state/event/log via applyDelta; objects collected on safe source callback |
| `handleGameEvents` | ProtocolGuiGame synchronizes control changes, collects/bundles deltas and events (or full-state fallback). | state/event/log via applyDelta; snapshot-restored undo forces full state |
| `updateZones` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateCard` | IGuiGame default calls updateCards(singleton(card)); inherited implementation is no-op. | local only: renderer refresh |
| `updateCards` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateCardsNetSafe` | IGuiGame default uses threadSafeIterable in net games then calls updateCards (no-op here). | local only: renderer refresh |
| `updateRevealedCards` | AbstractGuiGame merges existing reveal history into the collection and updates GameView. | state source mutation; history has no named 3.3 field (E10), distinct from temporary reveal event |
| `refreshCardDetails` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `refreshField` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateManaPool` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateLives` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `updateShards` | Inherited IGuiGame default no-op, marked adventure only. | local only: adventure renderer, no Commander message |
| `updateDependencies` | Inherited IGuiGame default no-op. | local only: renderer refresh; state/event stream supplies client updates |
| `getInteger` | Both overloads in AbstractGuiGame build choices; cutoff path can call numeric showInputDialog and parse the String. | prompt.kind=choose or number; not always a number dialog |
| `oneOrNone` | AbstractGuiGame returns null for no choices, otherwise getChoices(min=0,max=1) and first result. | prompt.kind=choose when needed |
| `one` | IGuiGame 2-argument default delegates; AbstractGuiGame handles empty/singleton locally, else getChoices(1,1). | prompt.kind=choose when needed |
| `reveal` | AbstractGuiGame calls getChoices(message,-1,-1,items). | prompt.kind=choose with negative sentinel bounds; not the reveal event |
| `many` | IGuiGame overloads delegate; AbstractGuiGame uses getChoices for max=1, otherwise order with remaining counts. | prompt.kind=choose or order |
| `insertInList` | AbstractGuiGame calls oneOrNone to pick insertion predecessor, then builds ordered list locally. | prompt.kind=choose; result reconstruction local |
| `setPlayerAvatar` | ProtocolGuiGame empty TODO stub. | local only: no transported avatar API (E10) |
| `openZones` | Inherited IGuiGame default no-op. | local only: renderer operation; no direct wire call |
| `canPickStackTargets` | ProtocolGuiGame returns true (IGuiGame default is false). | local only: capability enables selectStackItem |
| `isSelecting` | AbstractGuiGame tests whether selection.cards is nonempty. | local only: cached selection query |
| `isGamePaused` | AbstractGuiGame reads/writes gamePause field. | local only: playback UI; distinct from ProtocolGuiGame transport pause |
| `setGamePause` | AbstractGuiGame reads/writes gamePause field. | local only: playback UI; distinct from ProtocolGuiGame transport pause |
| `getGameSpeed` | AbstractGuiGame reads/writes PlaybackSpeed field. | local only: playback speed |
| `setGameSpeed` | AbstractGuiGame reads/writes PlaybackSpeed field. | local only: playback speed |
| `getDayTime` | AbstractGuiGame reads/writes daytime String. | local only: no day/night snapshot field in plan (E10) |
| `updateDayTime` | AbstractGuiGame reads/writes daytime String. | local only: no day/night snapshot field in plan (E10) |
| `cancelAwaitNextInput` | AbstractGuiGame cancels waiting timer and pending await task. | local only: timer cleanup; does not send promptCancel |
| `updateAutoPassPrompt` | ProtocolGuiGame sends awaitNextInput only when currentYieldMessage is non-null. | state.activeInput.kind=waiting; yieldState carries yield changes |
| `setCurrentPlayer` | AbstractGuiGame looks up player, checks local map, stores it; ProtocolGuiGame updateCurrentPlayer hook is empty. | local only: controller selection; no separate current-seat message |
| `isUiSetToSkipPhase` | ProtocolGuiGame returns false; host uses PlayerControllerHuman cache instead. | local only: unreachable GUI-side host query |
| `refreshYieldUi` | Inherited IGuiGame default no-op. | local only: renderer operation; no direct wire call |
| `awaitInput` | IGuiGame default waits on CountDownLatch. | local only: Input synchronization, not a transport reply |
| `isNetGame` | ProtocolGuiGame always returns true. | local only: enables remote-client behavior |
| `setNetGame` | AbstractGuiGame sets its networkGame flag; ProtocolGuiGame query already returns true. | local only: host mode flag |

## C. IGameController and IMacroSystem methods

All public method names are listed, including default methods. A getter or local setter is not automatically a remote endpoint.

| IGameController method | Client message or reason |
|---|---|
| `mayLookAtAllCards` | not exposed: developer visibility query; server filters each seat |
| `canPlayUnlimitedLands` | not exposed: developer rules query |
| `concede` | concede |
| `drawOfferAction` | ignored: decided out of v1 (plan 3.5 E12) |
| `alphaStrike` | attackAll.defenderId then alphaStrike |
| `useMana` | useMana.color |
| `selectButtonOk` | ok |
| `selectButtonCancel` | cancel |
| `selectPlayer` | selectPlayer.playerId; also composite defender selection |
| `selectCard` | selectCard.cardId/otherCardIds/button; also combat composites |
| `selectAbility` | selectAbility.abilityId (non-prompt Input path only) |
| `selectStackItem` | selectStackItem.stackId |
| `undoLastAction` | undo |
| `cheat` | not exposed: developer cheat object |
| `macros` | not exposed: local accessor; its three transported operations are below |
| `nextGameDecision` | nextGame.decision: rematch -> NextGameDecision.NEW, quit -> QUIT; CONTINUE unused (one game per match) |
| `getActivateDescription` | describeAbility.cardId -> abilityDescription.cardId/text; finished in task 1.12 area |
| `reorderHand` | reorderHand.cardId/index |
| `requestResync` | not exposed: no-op on PlayerControllerHuman; resync uses cached JSON then setResyncPending |
| `shouldAutoYield` | not exposed: local yield query |
| `getYieldController` | not exposed: local controller accessor |
| `setShouldAutoYield` | not exposed directly: setYield.cardAutoYield uses a precomputed storage key; NetGameController calls YieldController.setShouldAutoYield to encode scope |
| `setDisableAutoYields` | setYield.kind=disableYields, disabled=disable |
| `getTriggerDecision` | not exposed: local trigger decision query |
| `setTriggerDecision` | not exposed directly: setYield.triggerDecision uses a precomputed storage key; NetGameController calls YieldController.setTriggerDecision to encode scope |
| `setDisableAutoTriggers` | setYield.kind=disableTriggers, disabled=disable |
| `applyYieldUpdate` | not exposed directly: setYield is routed through sendYieldUpdate to applyYieldUpdate |
| `sendYieldUpdate` | setYield.kind and variant fields |
| `setYieldPref` | setYield.kind=pref, name=pref, value=String |

| IMacroSystem method | Client message or reason |
|---|---|
| `addRememberedAction` | not exposed: records local PlayerAction objects |
| `setRememberedActions` | macroRecord |
| `nextRememberedAction` | macroNext |
| `repeatRememberedActions` | macroRepeat.count; parameterless repeat called N times, stop on prompt |
| `cancelPlayback` | not exposed: no macro-cancel message in plan (E10) |
| `cancelCurrentMacro` | not exposed: default delegates to cancelPlayback; no macro-cancel message (E10) |
| `isRecording` | not exposed: local query; server pushes macroState.recording |
| `isReplaying` | not exposed: local query; server pushes macroState.replaying |
| `hasRememberedActions` | not exposed: local query; server pushes macroState.hasActions |
| `applyState` | not exposed: local mirror of macroState, not a client action |
| `consumeRememberedColorChoice` | not exposed: internal playback consumption, default null |
| `consumeRememberedManaCombo` | not exposed: internal playback consumption, default null |
| `consumeRememberedModeChoice` | not exposed: internal playback consumption, default null |
| `consumeRememberedAbilityOrder` | not exposed: internal playback consumption, default null |
| `consumeRememberedScry` | not exposed: internal playback consumption, default null |
| `playbackText` | not exposed: local display text; macroState supplies only three flags (E10) |

## D. Message catalog

Every message is an object discriminated by `type`. Root schema accepts either union; the named entry points are `#/$defs/ServerMessage` and `#/$defs/ClientMessage`, each a oneOf of message definitions with a distinct `properties.type.const`. This uses standard JSON Schema references, not the OpenAPI discriminator keyword.

All server messages require `seq: integer >= 0`, increasing per seat. Client game actions require `clientSeq` and `baseSeq` of that type; `baseSeq` is the state the player saw. Prompt replies require `requestId` and `result`. Only game actions require clientSeq/baseSeq (plan 3.5 E13); the existing lobby/heartbeat/promptReply behavior is retained: these fields are allowed but not required. Message-specific fields marked `?` are not universally required; this is a draft, not a decision that Forge-required prompt context can be omitted. Extra envelope fields are allowed while the plan gaps are open. Shared definitions with no declared type explicitly mark a representation the plan and sources do not yet determine.

`CardRef` is integer (visible) or opaque string (hidden); `PlayerRef`, abilityId and stackId are Java-int identities, not array positions. All ids in every direction pass through per-seat IdMapper; clients cannot act on hidden ids except when explicitly offered by their current prompt. `requestId` uses the existing IdentifiableNetEvent/IIdentifiable integer correlation type; plan 3.5 E13 scopes 32-bit integer ids to one game, with implementation finished in task 1.4. The opaque CardRef string alternative is retained. E8 is decided in task 1.2: `EntityRef` is `{kind, id}`, with kind `player` and a PlayerRef, `card` and a CardRef, or `stack` and an Int32 stack id. Planeswalkers and battles use kind `card`.

### Server to client

| Type | Fields beyond type and seq | Forge feed or server owner |
|---|---|---|
| `hello` | `protocolVersion`: const `1`; `serverVersion`: string; `seat`: integer | WsServer/seat handshake; no ProtocolMethod |
| `lobby` | `seats?`: array of JSON value (unresolved; see $comment) | New Lobby; no ProtocolMethod |
| `deckResult` | `ok`: boolean; `errors`: array of string | New DeckImport; no ProtocolMethod |
| `state` | `turn`: `Int32`; `activePlayerId`: `PlayerRef` or null; `priorityPlayerId`: `PlayerRef` or null; `phase`: `PhaseType` or null; `monarchId`: `PlayerRef` or null; `initiativeId`: `PlayerRef` or null; `players`: array of `Player`; `stack`: array of `StackItem`; `activeInput`: `ActiveInput` | setGameView and applyDelta with objects; display calls update activeInput |
| `event` | `kind`: `EventKind`; `stateSeq`: `Sequence`; `cardIds?`: array of `CardRef`; `playerIds?`: array of `PlayerRef`; `from?`: `ZoneRef` or null; `to?`: `ZoneRef` or null; `amount?`: `Int32`; `result?`: JSON value (unresolved; see $comment) | applyDelta.events / handleGameEvents and showRevealedCards; source table below |
| `log` | `text`: string; `entryType`: `GameLogEntryType` | GameEventAddLog.message via GameLog/GameLogEntry, PayloadFilter |
| `logTail` | `entries`: array of `LogEntry` ({text: string, entryType: GameLogEntryType, seq: Sequence}) | Last 200 filtered log entries on reconnect/resync; no separate Forge call |
| `prompt` | `requestId`: RequestId; `kind`: PromptKind; `title`: string; `options`: PromptOption[]; `min`, `max`: nonnegative integer; `default`: PromptResult; `sourceCardId?`: CardRef; kind-specific fields below | 12 blocking SERVER methods; sideboard answered locally |
| `promptCancel` | `requestId`: `RequestId` | New pending-request lifecycle; no ProtocolMethod equivalent |
| `message` | `level`: enum `"info"`, `"warning"`, `"error"`, `"rejected"`; `text`: string; `title?`: string | message, showErrorDialog, flashIncorrectAction; ActionRouter stale-action rejection |
| `macroState` | `recording`: boolean; `replaying`: boolean; `hasActions`: boolean | applyMacroState(IMacroSystem.State) |
| `clock` | none specified (see note below) | New timed-seat service; no ProtocolMethod |
| `yieldState` | `kind`: enum `"setMarker"`, `"clearMarker"`, `"stackYield"`, `"untilEndOfTurn"`, `"cardAutoYield"`, `"triggerDecision"`, `"disableYields"`, `"disableTriggers"`, `"skipPhase"`, `"pref"`, `"seed"`, `"clearAbilityOrders"` | applyYieldUpdate(YieldUpdate) |
| `pong` | none specified (see note below) | WsServer heartbeat reply; no ProtocolMethod |
| `attention` | none | alertUser; taskbar flash when the action is on this seat and the window is not focused |
| `abilityDescription` | `cardId`: `CardRef`; `text`: string | getActivateDescription reply to describeAbility; finished in task 1.12 area |

`lobby` describes seats, names (strings), deck status (representation unspecified), ready flags (booleans); exact nesting is E11. `deckResult` also needs parsed commander (identity representation unspecified) and count (integer), whose field names are absent from 3.1. `clock` contains per-seat `remainingMs: integer >= 0` and `running: boolean` (`SeatClock`), but its container and seat-id field are unspecified. `logTail.entries` contains the last 200 filtered entries on reconnect/resync; each entry requires text, entryType and its original seq. `log.type` remains `log`, and required `entryType` uses Forge enum names rather than captions (plan 3.5 E2; finished in task 1.8). GameLogEntryType values: `GAME_OUTCOME`, `MATCH_RESULTS`, `TURN`, `MULLIGAN`, `ANTE`, `DRAFT`, `ZONE_CHANGE`, `PLAYER_CONTROL`, `DAMAGE`, `LIFE`, `LAND`, `DISCARD`, `COMBAT`, `INFORMATION`, `STACK_RESOLVE`, `STACK_ADD`, `EFFECT_REPLACED`, `MANA`, `PHASE`. `pong` has no further plan fields.

### Client to server

| Type | Fields beyond type/clientSeq/baseSeq | Consumer |
|---|---|---|
| `join` | `name`: string; `seatToken?`: string; `protocolVersion`: const `1` | New Lobby/reconnect authentication |
| `submitDeck` | `text`: string | New DeckImport; DeckRecognizer and Commander validation planned |
| `ready` | none | New Lobby readiness |
| `startGame` | none | New Lobby host start; HostedMatch startMatch later |
| `lobbySettings` | `startingLife?`: `Int32`; `clock?`: enum `"untimed"`, `30`, `45`; `notes?`: string | New Lobby; RegisteredPlayer.setStartingLife for life |
| `selectCard` | `cardId`: `CardRef`; `button?`: enum `1`, `3`; `otherCardIds?`: array of `CardRef` | IGameController.selectCard |
| `selectPlayer` | `playerId`: `PlayerRef` | IGameController.selectPlayer |
| `selectAbility` | `abilityId`: `Int32` | IGameController.selectAbility; not a blocking prompt answer |
| `selectStackItem` | `stackId`: `Int32` | IGameController.selectStackItem |
| `ok` | none | IGameController.selectButtonOk |
| `cancel` | none | IGameController.selectButtonCancel |
| `undo` | none | IGameController.undoLastAction |
| `concede` | none | IGameController.concede after client confirmation |
| `macroRecord` | none | controller.macros().setRememberedActions |
| `macroNext` | none | controller.macros().nextRememberedAction |
| `resync` | none | Cached state/prompt resend, ProtocolGuiGame.setResyncPending; not requestResync |
| `ping` | none | WsServer heartbeat; no Forge method |
| `useMana` | `color`: integer | IGameController.useMana |
| `declareAttacker` | `attackerId`: `CardRef`; `defenderId`: `EntityRef` | Select defender, then selectCard(attacker); idempotence/composite rules below |
| `undeclareAttacker` | `attackerId`: `CardRef` | selectCard(attacker, trigger button 3) |
| `declareBlocker` | `blockerId`: `CardRef`; `attackerId`: `CardRef` | Select attacker then selectCard(blocker); move-safe composite rules below |
| `undeclareBlocker` | `blockerId`: `CardRef` | selectCard(blocker, trigger button 3) |
| `attackAll` | `defenderId`: `EntityRef` | Select defender then IGameController.alphaStrike |
| `reorderHand` | `cardId`: `CardRef`; `index`: `Int32` | IGameController.reorderHand |
| `promptReply` | `requestId`: `RequestId`; `result`: `PromptResult` | Completes JsonSeatRemote pending future directly; Forge receives converted return value |
| `setYield` | `kind`: enum `"setMarker"`, `"clearMarker"`, `"stackYield"`, `"untilEndOfTurn"`, `"cardAutoYield"`, `"triggerDecision"`, `"disableYields"`, `"disableTriggers"`, `"skipPhase"`, `"pref"`, `"seed"`, `"clearAbilityOrders"` | IGameController.sendYieldUpdate -> applyYieldUpdate |
| `macroRepeat` | `count`: integer 1..99 | controller.macros().repeatRememberedActions repeated count times |
| `nextGame` | `decision`: enum `"rematch"`, `"quit"` | IGameController.nextGameDecision: rematch -> NEW (same decks, fresh game), quit -> QUIT; CONTINUE unused (one game per match) |
| `describeAbility` | `cardId`: `CardRef` | IGameController.getActivateDescription -> abilityDescription {cardId, text}; finished in task 1.12 area |

Section 2.5 is more precise than the compressed 3.2 table: undeclareAttacker needs only attackerId, and undeclareBlocker only blockerId. A defender can be a player, planeswalker or battle; tagged EntityRef separates their identity namespaces. Combat validation must reject stack references and any other illegal defender. For declareAttacker, do nothing if already attacking that defender, otherwise remove an old assignment, select defender then attacker. For declareBlocker, do nothing if the assignment exists; remove an incompatible old assignment, select the attacker unless already chosen, then select blocker. Each composite runs in one EDT task. `cancel` uses Forge button semantics: during attacks it can alpha-strike or recall attackers, so labels must come from updateButtons.

### Blocking prompts and results

All wire prompts require type, seq, requestId, kind, title, options, min, max and default. sourceCardId is optional. Each option has a prompt-scoped integer optionId (0..n-1) and server-rendered label. Card options additionally carry cardRef and available name/set/num. Under plan 3.5 E5 as corrected for task 1.6, the owner-only prompt reveals offered face-up cards even from libraries or other players' hands. Face-down cards retain the seat's normal CardVisibility permissions: if identity is hidden, the option has label "Face-down card", an opaque cardRef, and no name/set/num. Its alternate state and custom identity formatter are not read. The same rule applies to delayed-reveal metadata. An existing IdMapper ref is reused; otherwise a random prompt-only opaque ref is allocated without granting general action access. getChoices' display function is applied on the Forge caller, never serialized or invoked on I/O. Non-card objects (including CostPart) stay in the server's option table. Desktop icon objects and trigger-event objects are presentation context, not wire objects.

| Kind | Extra JSON context | promptReply.result and Forge return |
|---|---|---|
| choose | selected: optionId[], revealOnly: boolean | unique optionId[] within min..max -> List<T>; reveal -1/-1 becomes min=max=0, result [] |
| chooseEntities | single: boolean, optional?: boolean, revealed?: card metadata[], revealMessage?: string | unique optionId[] -> one GameEntityView (null only for allowed empty selection) or List<GameEntityView> |
| order | source/destination: optionId[], remainingMin/remainingMax: integer, top: string, sideboardingMode/rememberAllowed: boolean | {ordered: unique optionId[], remember: boolean} -> OrderResult(ordered, rememberDecision) |
| confirm | text: string, option labels, default: boolean | boolean |
| option | text: string, option labels, default: integer | integer index in options, or -1 for cancellation |
| number / text | text: string, initialInput: string or null, restrictedInput: boolean and optional input-options labels | string or null (when restricted, one of those offered values); number accepts an integer string, never a JSON number |
| assignCombatDamage | amount: integer, lethal: integer[], overrideOrder/divideDamage/maySkip: boolean, defenderOptionId?: integer | [{optionId,amount}] -> Map<CardView,Integer>; amounts sum to damage, lethal before later recipients unless permitted; defender uses a null map key; null only when maySkip |
| assignGeneric | amount: integer, capacities: integer[], atLeastOne: boolean | [{optionId,amount}] -> Map<Object,Integer>; sum, capacities and atLeastOne per target enforced |
| manipulateList | manipulable: optionId[], toTop/toBottom/toAnywhere: boolean | permutation of all optionIds -> List<CardView>; fixed relative order and movement restrictions enforced |
| chooseAbility | sourceCardId, offered ability labels | one optionId -> the exact offered SpellAbilityView (not an abilityId action) |

Sideboard never goes on the wire: its local result is the unchanged main CardPool.toFlatList(), matching PlayerControllerHuman.sideboard's no-change semantics.

**Order destination rule verified in source:** DualListBox.getOrderedList (forge-gui-desktop/src/main/java/forge/gui/DualListBox.java) returns the destination list; its min/max constrain the number remaining in the source. The wire min/max instead describe the returned destination count: total - remainingMax through total - remainingMin. Initial source and destination together form the option table; initial destination entries may move back. PlayerControllerHuman's orderBlockers/orderAttackers/orderCosts, scry/surveil and orderMoveToZoneList require a full permutation (remaining 0/0). Exert/enlist allow subsets. Replacement effects use a prepopulated destination and require at least one result. Simultaneous abilities may arrive wholly in either list and optionally remember the order. orderCosts supplies CostPart objects. Library movement reverses iteration after the GUI choice so the first returned card remains nearest the destination end.

Defaults are decoded through the same validator as replies: first min choices, first single entity, default confirm/index, null cancelled text, unchanged manipulation, first ability, lethal damage then the last blocker/defender, and first generic recipient subject to capacity/atLeastOne constraints. A null order destination preserves source order (subject to legal destination size); a supplied destination uses the smallest legal selection, retaining its entries first. No default returns null for order, choose, or allocations.

A reply's requestId selects the stored kind and option table; the schema accepts the union of per-kind result shapes, and PromptCodec enforces the correlated type and semantic constraints. Invalid replies send message.level=rejected and leave the same future pending. Unknown/finished IDs are ignored and logged. Completion runs directly on Netty I/O, without joining the action queue. Disconnect retains futures without timeout. Reconnect sends hello, cached state, then pending prompts with their original envelopes/requestIds; replayed sequence numbers identify old messages, not newly produced deltas. All normal messages still pass through SeatWriter ordering. Host abandonment consumes these legal defaults in task 1.14.

### Yield tagged union

Both setYield and yieldState use these kind-specific fields in addition to their respective envelope. The schema names the shared union YieldUpdate and its seed object YieldSeed.

| kind | JSON fields | Forge record |
|---|---|---|
| setMarker | `phaseOwnerId`: `PlayerRef`; `phase`: `PhaseType` | YieldUpdate.SetMarker |
| clearMarker | none | YieldUpdate.ClearMarker |
| stackYield | `active`: boolean; `respectsInterrupts`: boolean | YieldUpdate.StackYield |
| untilEndOfTurn | `active`: boolean | YieldUpdate.SetAutoPassUntilEndOfTurn |
| cardAutoYield | `cardKey`: string; `active`: boolean | YieldUpdate.CardAutoYield |
| triggerDecision | `storageKey`: string; `decision`: `TriggerDecision` | YieldUpdate.TriggerDecision |
| disableYields | `disabled`: boolean | YieldUpdate.SetDisableYields |
| disableTriggers | `disabled`: boolean | YieldUpdate.SetDisableTriggers |
| skipPhase | `turnPlayerId`: `PlayerRef`; `phase`: `PhaseType`; `skip`: boolean | YieldUpdate.SkipPhase |
| pref | `name`: `YieldPref`; `value`: string | YieldUpdate.SetYieldPref |
| seed | `snapshot`: `YieldSeed` | YieldUpdate.SeedFromClient |
| clearAbilityOrders | none | YieldUpdate.ClearAbilityOrders |

The authenticated seat supplies player fields omitted from the client envelope; the server computes SetMarker.atOrPastAtClick from the live phase. TriggerDecision is ASK/ACCEPT/DECLINE from AutoYieldStore. `pref.value` is a **string**, even for booleans. The preference-name allow list exactly copies YieldController.SYNCED_PREFS (currently 17 names) into YieldPref; no access to its private field is needed. NetGameController computes storage keys through YieldController: per-ability scope uses AutoYieldStore.abilitySuffix(key), and per-card scope retains the full key. This is why the envelope has no isAbilityScope flag. Per-seat highlighting uses UI_SHOW_ACTIONABLE_HIGHLIGHTS and UI_SHOW_AUTOTAP_PREVIEW.

YieldSeed copies the public YieldStateSnapshot record: `cardYields`, `abilityYields`: unique string arrays; `cardTriggerDecisions`, `abilityTriggerDecisions`: string-keyed objects with TriggerDecision values; `autoYieldsDisabled`, `autoTriggersDisabled`: booleans; `prefOverrides`: YieldPref-keyed object of strings; `skipPhases`: Map<PlayerView,EnumSet<PhaseType>> with unresolved JSON object-key representation (E6). The same plan union omits some fields needed to mirror authoritative Forge updates; E6 keeps those open.

### State snapshot

The state envelope flattens StateSnapshot alongside type/seq, matching section 3.3 (no extra snapshot wrapper). The section 3.3 example, updated with the task 1.2 E8 reference and commander shapes, is:

```json
{
  "type": "state", "seq": 412,
  "turn": 7, "activePlayerId": 2, "priorityPlayerId": 0,
  "phase": "COMBAT_DECLARE_ATTACKERS", "monarchId": 3, "initiativeId": null,
  "players": [
    { "id": 0, "name": "Will", "life": 31, "poison": 0,
      "commanderDamage": [ { "commanderId": 101, "damage": 6 }, { "commanderId": 102, "damage": 0 }, { "commanderId": 103, "damage": 0 } ],
      "commanders": [ { "cardId": 100, "casts": 1, "tax": 2 } ],
      "manaPool": { "W": 0, "U": 1, "B": 0, "R": 0, "G": 2, "C": 0 },
      "zones": {
        "hand": [ { "id": 88, "name": "Swords to Plowshares", "set": "sta", "num": "10" } ],
        "library": { "count": 61 },
        "graveyard": [ ], "exile": [ ], "command": [ ],
        "battlefield": [ ]
      },
      "eliminated": false }
  ],
  "stack": [ { "stackId": 5, "sourceCardId": 140, "controllerId": 1, "text": "...", "targets": [ { "kind": "card", "id": 88 } ] } ],
  "activeInput": {
    "kind": "declareAttackers",
    "prompt": "Declare attackers",
    "okLabel": "Confirm", "cancelLabel": "Alpha strike",
    "buttons": { "ok": { "enabled": true }, "cancel": { "enabled": true } },
    "highlighted": [ { "id": 301, "strength": 1 }, { "id": 302, "strength": 1 } ],
    "chosen": [ { "kind": "player", "id": 1 } ],
    "attackTargets": [ { "kind": "player", "id": 1 }, { "kind": "player", "id": 2 }, { "kind": "card", "id": 410 } ]
  }
}
```

| Shared shape | Fields and JSON types |
|---|---|
| StateSnapshot | `turn`: `Int32`; `activePlayerId`: `PlayerRef` or null; `priorityPlayerId`: `PlayerRef` or null; `phase`: `PhaseType` or null; `monarchId`: `PlayerRef` or null; `initiativeId`: `PlayerRef` or null; `players`: array of `Player`; `stack`: array of `StackItem`; `activeInput`: `ActiveInput` |
| Player | `id`: `PlayerRef`; `name`: string; `life`: `Int32`; `poison`: `Int32`; `commanderDamage`: array of `CommanderDamage`; `manaPool`: `ManaPool`; `zones`: `Zones`; `eliminated`: boolean; `commanders`: array of `Commander` |
| EntityRef | tagged object: `kind`: const `"player"` with `id`: `PlayerRef`, const `"card"` with `id`: `CardRef`, or const `"stack"` with `id`: `Int32`; both fields required |
| CommanderDamage | `commanderId`: `CardRef`; `damage`: `Int32`; both required |
| Commander | `cardId`: `CardRef`; `casts`: `Int32`; `tax`: `Int32`; all required |
| ManaPool | `W`: `Int32`; `U`: `Int32`; `B`: `Int32`; `R`: `Int32`; `G`: `Int32`; `C`: `Int32` |
| Zones | `hand`: array of `Card`; `library`: object; `graveyard`: array of `Card`; `exile`: array of `Card`; `command`: array of `Card`; `battlefield`: array of `Card` |
| VisibleCard | `id`: `CardRef`; `name?`: string; `set?`: string; `num?`: string; `tapped?`: boolean; `sick?`: boolean; `power?`: `Int32`; `toughness?`: `Int32`; `damage?`: `Int32`; `counters?`: `Counters`; `attachedTo?`: `EntityRef` or null; `attacking?`: boolean; `blocking?`: boolean; `attackTarget?`: `EntityRef` or null; `faceDown?`: boolean; `controllerId?`: `PlayerRef`; `ownerId?`: `PlayerRef`; `token?`: boolean; `isCommander?`: boolean; `hidden?`: const `false`; `blockingIds?`: array of `CardRef` |
| HiddenCard | `id`: string; `hidden`: const `true` |
| FaceDownCard | `id`: `CardRef`; `tapped?`: boolean; `sick?`: boolean; `power?`: `Int32`; `toughness?`: `Int32`; `damage?`: `Int32`; `counters?`: `Counters`; `attachedTo?`: `EntityRef` or null; `attacking?`: boolean; `blocking?`: boolean; `attackTarget?`: `EntityRef` or null; `faceDown`: const `true`; `controllerId?`: `PlayerRef`; `ownerId?`: `PlayerRef`; `token?`: boolean; `isCommander?`: boolean; `blockingIds?`: array of `CardRef` |
| StackItem | `stackId`: `Int32`; `sourceCardId`: `CardRef`; `controllerId`: `PlayerRef`; `text`: string; `targets`: array of `EntityRef` |
| ActiveInput | `kind`: enum (see below); `prompt?`: string; `sourceCardId?`: `CardRef`; `manaPoolOpen?`: boolean; `okLabel?`: string; `cancelLabel?`: string; `buttons?`: object; `highlighted?`: array of object; `chosen?`: array of `EntityRef`; `attackTargets?`: array of `EntityRef`; `min?`: `Int32`; `max?`: `Int32`; `selectable?`: array of `CardRef`; `targets?`: array of `EntityRef` |
| SeatClock | `remainingMs`: integer; `running`: boolean |

ActiveInput.buttons is `{ok:{enabled:boolean},cancel:{enabled:boolean}}`; highlighted is `[{id:CardRef,strength:1 or 2}]`. Chosen, attackTargets and targets use tagged EntityRef objects, as do stack.targets, defenderId, attachedTo and attackTarget. These replace the original playerId/planeswalkerId forms. E7 is decided in task 1.5. Waiting carries only kind, suppressing stale actions; other kinds derive their available fields from Selection, updateButtons, showPromptMessage and highlight calls. sourceCardId is the prompt card; manaPoolOpen records showManaPool/hideManaPool (initially false). Optional fields appear once Forge supplies them. attackTargets appears only during declareAttackers and includes every live Combat defender. Button owner is implicit in the receiving seat; focus is a local UI hint.

| Input class | activeInput.kind |
|---|---|
| InputPassPriority | priority |
| InputAttack | declareAttackers |
| InputBlock | declareBlockers |
| InputSelectTargets | target |
| InputPayMana, InputPayManaOfCostPayment | payMana |
| InputSelectCardsFromList | selectCards |
| InputSelectEntitiesFromList | selectEntities |
| InputSelectCardsForConvokeOrImprovise | convoke |
| InputSelectStackItems | selectStack |
| InputConfirm | confirm |
| InputConfirmMulligan, InputLondonMulligan, InputChooseStartingHand | mulligan |
| InputLockUI, no input, awaitNextInput | waiting |
| Other Input classes | select |

These mappings yield 13 distinct kinds. Exact classes take precedence over known subclasses; unknown classes use Selection and buttons and are logged once. selectable comes from Selection.cards, with min/max, targets and chosen copied from the same Selection. Every card position, including highlights and tagged references, uses IdMapper.currentRef; absent references are omitted and logged once until prompt-offered ids arrive in task 1.7. Duplicate highlights have strength 2. Display changes send a copy of the latest snapshot with a new sequence through normal SeatWriter coalescing; they never walk the game graph. InputQueue and legal defenders are read only within EDT/Game-thread display calls. Timer calls retain the last classification.

ZoneType contains all Forge enum spellings (Hand through None), while the snapshot zone **property names** are lowercase hand/library/graveyard/exile/command/battlefield. Library is `{count:integer}`; the other snapshot zones are arrays of Card. Counter maps have CounterType.toString() keys and integer counts (not localized display names); a null Forge counter multiset becomes an empty object. Card fields are optional unless id/hidden/faceDown identifies the variant, because the plan does not settle the complete required set for each zone. All visibility checks must run server-side: canBeShownTo alone does not hide face-down identity; also use canFaceDownBeShownTo. Hidden cards contain only opaque id and hidden=true. Unknown face-down cards omit name/set/num but keep allowed public characteristics. IdMapper reissues hidden ids on hidden-zone re-entry and face-down transitions; do not expose Forge object graphs or hidden-id fields.

The complete getter inventory, return types, source lines and visibility rules are in [state-fields.md](state-fields.md). Player.commanderDamage queries the receiving PlayerView.getCommanderDamage for each commander card, including opponents' commanders. Player.commanders lists owned commanders from PlayerView.getCommanders, with casts from PlayerView.getCommanderCast and tax = 2 * casts (CostAdjustment.java:58); tax is the base commander surcharge before other cost adjustments, not the final payable cost. Card.getPaperCard().getCollectorNumber supplies num; Game.getMonarch/getHasInitiative supply designations. Combat.getAttackersBlockedBy supplies blockingIds (all attackers blocked by the card, including bands); retain blocking, with blockingIds omitted or empty when not blocking. Combat.getDefenderByAttacker supplies nullable attackTarget. Read these live APIs only on the game-safe callback.

### Event kinds and sources

All source records below are in forge-game/src/main/java/forge/game/event; events arrive through applyDelta/handleGameEvents except the explicitly listed protocol/snapshot paths. Kind-specific payload layout remains task 1.8 (E9).

| kind | Verified source and relevant fields |
|---|---|
| zoneChange | GameEventCardChangeZone(card, from, to); nullable ZoneView(player, zoneType) endpoints |
| landPlayed | GameEventLandPlayed(player, land) |
| spellCast | GameEventSpellAbilityCast(sa, si, stackIndex, targetDescription), includes activated abilities |
| resolved | GameEventSpellResolved(spell, hasFizzled, stackDescription), non-fizzled |
| countered | hasFizzled=true, or unmatched GameEventSpellRemovedFromStack(sa); normal resolution also removes from stack, so correlate first |
| tap / untap | GameEventCardTapped(card, tapped) |
| damage | GameEventCardDamaged(card, source, amount, type); GameEventPlayerDamaged(target, source, amount, combat, infect) |
| counterChange | GameEventCardCounters(card, type, oldValue, newValue); GameEventPlayerCounters(receiver, type, oldValue, amount) |
| tokenCreated | GameEventTokenCreated has no fields; pair with subsequent zone changes in the batch for ids |
| attack | GameEventAttackersDeclared(player, attackersMap) |
| block | GameEventBlockersDeclared(defendingPlayer, blockers) |
| shuffle | GameEventShuffle(player) |
| coinFlip | GameEventFlipCoin(flipper, heads boolean, startingToss boolean) |
| dieRoll | GameEventRollDie(sides int, result int, planar PlanarDice); GameEventRollDice is a separate plural event not mapped by plan (E9) |
| mulligan | GameEventMulligan(player) |
| turnBegan | GameEventTurnBegan(turnOwner, turnNumber) |
| reveal | showRevealedCards, filtered to permitted seat |
| lifeChange | Snapshot difference in PlayerView.getLife (plan choice, although GameEventPlayerLivesChanged also exists) |
| triggerFired | Stack difference with StackItemView.isTrigger |
| monarchChange / initiativeChange | Snapshot designation difference via Game.getMonarch / Game.getHasInitiative |
| playerEliminated | PlayerView.getHasLost change |
| gameOver | GameEventGameOutcome(lastTurnNumber, outcomeStrings, winningPlayerName, matchSummary) |

A snapshot is built only inside Forge-safe setGameView or object-bearing applyDelta delivery, including the updateGameView emitted before highlight sends. Events-only deltas never cause an off-thread graph walk. Only finished JSON crosses to SeatWriter. States coalesce (10/s maximum), events/prompts/logs retain order; an event carries stateSeq and its prerequisite state must be sent first. Netty I/O threads never wait or take locks; all actions run in order on the common EDT. Bridge leaf locks are never held across Forge calls, another seat lock, or future waits. Stale actions are rejected only when they no longer apply, using message level rejected and a reason; resync sends cached JSON immediately and schedules a safe rebuild with setResyncPending.

## E. Gaps and questions for Will

The original task 1.1 questions are retained below, followed by their plan 3.5 decisions or defaults. E4, E10 and E12 are decided; E8 is decided in task 1.2, E7 in task 1.5 and E5 in task 1.6. The other items use defaults and are finished in the named tasks; E6, E9 and E11 retain their current schema shapes until then.

- **E1.** Will, what server message and correlation fields should carry the String returned by describeAbility/getActivateDescription? Section 3.2 promises a tooltip reply but 3.1 names none.

  Default (plan 3.5): New server message `abilityDescription {cardId, text}` answers `describeAbility`. Finished in task 1.12 area.

  Status: default, finished in task 1.12 area

- **E2.** Will, what field should hold GameLogEntryType when log.type must remain the discriminator "log"? Is logTail a separate message or a field in another message, and what is its entries envelope?

  Default (plan 3.5): `log` gains `entryType` (Forge `GameLogEntryType`); `logTail` is its own message with `entries[]`. Finished in task 1.8.

  Status: default, finished in task 1.8

- **E3.** Will, what are the allowed message.level values besides rejected, how should message/showErrorDialog titles be represented, and where should the rejection reason come from when flashIncorrectAction supplies no text?

  Default (plan 3.5): `message.level` is one of `info`, `warning`, `error`, `rejected`; optional `title`. `flashIncorrectAction` becomes `rejected` with a generic text; `ActionRouter` rejections carry their own reason. Finished in task 1.8.

  Status: default, finished in task 1.8

- **E4.** Will, does nextGame.decision=rematch mean NextGameDecision.NEW or CONTINUE, and is the other decision intentionally unavailable? Forge exposes NEW, CONTINUE and QUIT.

  Decision (plan 3.5): **Rematch = `NEW`** (same decks, fresh game), quit = `QUIT`; `CONTINUE` unused.

  Status: decided

- **E5.** Will, what are the complete option and reply encodings for each prompt kind, including generic values, single versus multiple entities, nullable/cancel results, OrderResult.rememberDecision, card/byte allocation maps, sideboard PaperCards, initial/default/selected values, icons, delayed reveals, damage/defender/amount context and manipulation flags? Should numeric-dialog replies remain String/null as Forge requires, and how are prompt-bound choice ids scoped?

  Decision (plan 3.5 E5, corrected in task 1.6): Prompt-scoped optionIds and owner-only reveals apply to offered face-up cards. Face-down identities are included only when CardVisibility.forSeat permits them; otherwise use "Face-down card" with an opaque ref and no identity metadata. Exact server object conversion, per-kind validation/defaults, and local no-change sideboard are implemented; see section D.

  Status: decided (task 1.6)

- **E6.** Will, how should Unity obtain the full-card versus ability-suffix storage keys used by setYield, and how should yieldState preserve player identity and SetMarker.atOrPastAtClick when mirroring Forge? What JSON encoding should YieldStateSnapshot.skipPhases use, and are the copied public seed-record field names the intended wire names?

  Default (plan 3.5): The client sends card and ability ids for yields; the server computes Forge's storage keys. Finished in task 1.13.

  Status: default, finished in task 1.13

- **E7.** Will, what are the complete activeInput kind names and context fields for priority, targeting, mana payment, mulligan and fallback selection? Are selectable/targets the intended names, and where do prompt card, button owner/focus, and showManaPool/hideManaPool state belong?

  Decision (task 1.5): ActiveInputTracker defines the kinds and fields in section D, including prompt sourceCardId, tagged chosen/targets, mapped highlights and manaPoolOpen; selection-only changes produce states from cached JSON.

  Status: decided (task 1.5)

- **E8.** Will, how should mixed player/card/stack/battle references be tagged (including defenderId and flat stack.targets), and how should combat blocker assignments be represented beyond CardView.isBlocking? Are commanderDamage keys commander-card ids or player ids, where does commander tax live, and which card/state fields must be required? Counter key spelling, collector-number/designation access and required public face-down fields need confirmation in task 1.2.

  Decision (task 1.2): EntityRef uses `{kind, id}` tags for player/card/stack; commanderDamage is keyed by commander card, commanders carries casts and base tax (2 * casts), blockingIds lists blocked attackers, and [state-fields.md](state-fields.md) fixes the public getter paths, counter keys and visibility rules; existing optional card fields remain optional.

  Status: decided (task 1.2)

- **E9.** Will, what are the per-kind event payloads, zone endpoint shape, counter type/change fields, die-size field and planar-die result encoding? Should plural GameEventRollDice and other currently unlisted events (day/night, flip-onto-battlefield, snapshot restoration) get kinds, and what log fallback/correlation rules apply in task 1.8?

  Default (plan 3.5): Per-kind event payloads are defined with `EventPump`. Finished in task 1.8.

  Status: default, finished in task 1.8

- **E10.** Will, should alertUser, overlay enable/disable, finishGame/afterGameEnd/openView UI lifecycle, setPanelSelection/setCard focus, hideRevealedCards and reveal history, avatar/renderer capability, day/night UI state, and macro cancellation/playback text remain local/ignored, or receive named messages/fields? No corresponding wire representation is specified.

  Decision (plan 3.5): **`alertUser` becomes an `attention` message** (taskbar flash when the action is on you). Every other item in E10 stays local or ignored.

  Status: decided

- **E11.** Will, what are the exact lobby seat/deck-status and deckResult commander/count shapes, clock container and seat identifiers, lobbySettings update semantics and clock encoding, and first-join seat-token issuance/response? These messages belong to new Arena services, not ProtocolMethod. Which lifecycle source emits promptCancel, and which service owns clock/ping/pong scheduling?

  Default (plan 3.5): Lobby, deck result and clock shapes are defined with the lobby work. Finished in task 1.11, 1.12.

  Status: default, finished in task 1.11, 1.12

- **E12.** Will, confirm that drawOfferAction and updateDrawOffer stay ignored: section 3.3 explicitly excludes draw offers in v1, while this task cites drawOfferAction as a possible gap. No replacement message is added here.

  Decision (plan 3.5): **Draw offers stay out of v1** (`drawOfferAction`, `updateDrawOffer` ignored).

  Status: decided

- **E13.** Will, which non-game messages require clientSeq/baseSeq, how are sequence/request ids sized and scoped across reconnects/games, what bounds apply to macroRepeat.count, and where/how is the client protocolVersion reported for the mutual mismatch check? The current join fields do not carry a client version.

  Default (plan 3.5): Only game actions carry `clientSeq` and `baseSeq`; ids are 32-bit integers scoped to one game; `join` carries the client's `protocolVersion`; `macroRepeat.count` is capped at 99. Finished in task 1.4.

  Status: default, finished in task 1.4
