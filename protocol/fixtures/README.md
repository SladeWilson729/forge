# Seat 0 wire fixtures

These are original UTF-8 socket payloads from real, started four-player Commander
games. They are not normalized: sequence numbers, request ids, real ids and opaque
ids are specific to the captured game. Each input file is a **complete state
message**, not just an ActiveInput object. Do not combine unrelated fixture files
into one game or assume a globally meaningful card id.

`WireFixtureCatalogTest` captures the seat-0 `out` payload verbatim from TranscriptLog
(which records only writes that reach that seat's socket). Each `.reply.json` is
the corresponding original `in` payload with the server's `result: accepted`
verdict in that same transcript. Transcript envelopes and acceptance metadata are
not included in the fixture. No prompt ids, option labels or reply values are
invented by the catalog writer. All JSON validates against `protocol/schema.json`.

Normal test runs validate the checked-in catalog and independently recapture all
available kinds to `forge-arena-server/target/wire-fixtures-*/catalog/`. They do not
rewrite source files. To regenerate intentionally, from the repo root:

```powershell
mvn --% -pl forge-arena-server -am test -Dtest=WireFixtureCatalogTest -Darena.fixtures.write=true -Dsurefire.failIfNoSpecifiedTests=false
```

Review the generated diff before committing. Run the complete suite with:

```powershell
mvn --% -pl forge-arena-server -am test -Dtest=forge.arena.server.**.*Test -Dsurefire.failIfNoSpecifiedTests=false
```

## Prompt files and accepted replies

The decision probes use PromptGameHarness's real human seat, protocol GUI, socket,
writer and IdMapper. The main game thread remains parked at priority while a Game
pool worker invokes public PlayerControllerHuman decision methods used by effects
and combat. This isolates a decision without needing a full card combo for every
UI variant. The methods construct their own GUI calls/Inputs; the test never
constructs a GuiGameEvent, fake Input or prompt JSON. Responses cross the real
socket and are decoded back into Forge's offered objects. The regular Guildmage
action also exercises a prompt blocking the EDT inside selectCard.

| Prompt file | Accepted reply file | Scenario / notable fields |
| --- | --- | --- |
| [prompts/choose.json](prompts/choose.json) | [prompts/choose.reply.json](prompts/choose.reply.json) | Human `chooseNumber` with finite choices; options have prompt-scoped optionId and label. Reply is an option-id array, not the selected numeric value. |
| [prompts/choose-revealOnly.json](prompts/choose-revealOnly.json) | [prompts/choose-revealOnly.reply.json](prompts/choose-revealOnly.reply.json) | Human `reveal` of two library cards calls AbstractGuiGame.reveal; revealOnly=true, min=max=0, reply=[] acknowledges. These face-up offered cards are revealed only to this prompt's seat. |
| [prompts/chooseEntities.json](prompts/chooseEntities.json) | [prompts/chooseEntities.reply.json](prompts/chooseEntities.reply.json) | Human `chooseSingleEntityForEffect` library search with card-display selection off; single=true; reply=[optionId]. |
| [prompts/chooseEntities-multiple.json](prompts/chooseEntities-multiple.json) | [prompts/chooseEntities-multiple.reply.json](prompts/chooseEntities-multiple.reply.json) | Human `chooseEntitiesForEffect`, two library choices, min=1/max=2; single=false. |
| [prompts/order.json](prompts/order.json) | [prompts/order.reply.json](prompts/order.reply.json) | Human `orderBlockers`: Colossal Dreadmaw versus two bears; full order, sourceCardId, top caption, remainingMin=remainingMax=0. No destination list supplied by Forge. |
| [prompts/order-destination.json](prompts/order-destination.json) | [prompts/order-destination.reply.json](prompts/order-destination.reply.json) | Human `arrangeForScry`, card-display selection off: select one of two library cards for the destination (bottom). Reply.ordered is the destination selection, not a permutation of all options. source/destination are option-id arrays; destination starts empty. See destination limitation below. |
| [prompts/confirm.json](prompts/confirm.json) | [prompts/confirm.reply.json](prompts/confirm.reply.json) | Human `confirmAction` through InputConfirm's blocking dialog path (`isLibgdxPort=true`, same harness switch used by PromptRoundTripTest); text, labels and boolean result. |
| [prompts/number.json](prompts/number.json) | [prompts/number.reply.json](prompts/number.reply.json) | Human `chooseNumber(0, Integer.MAX_VALUE)` -> getInteger -> choose Other -> numeric input. Result is the string "12", not JSON number 12. |
| [prompts/assignCombatDamage.json](prompts/assignCombatDamage.json) | [prompts/assignCombatDamage.reply.json](prompts/assignCombatDamage.reply.json) | Human `assignCombatDamage` for Grizzly Bears against two opposing bears; amount=2, lethal thresholds; no defender option. |
| [prompts/assignCombatDamage-trample.json](prompts/assignCombatDamage-trample.json) | [prompts/assignCombatDamage-trample.reply.json](prompts/assignCombatDamage-trample.reply.json) | Human `assignCombatDamage` for Colossal Dreadmaw against those blockers; amount=6; defenderOptionId receives the remaining two trample damage. Defender converts back to Forge's null map key. |
| [prompts/assignGeneric.json](prompts/assignGeneric.json) | [prompts/assignGeneric.reply.json](prompts/assignGeneric.reply.json) | Human `divideShield`, three shields divided among a creature and a player; capacities, amount, atLeastOne=false. |
| [prompts/manipulateList.json](prompts/manipulateList.json) | [prompts/manipulateList.reply.json](prompts/manipulateList.reply.json) | Human `arrangeForScry`, card-display selection on; library list plus manipulable top two; toTop/toBottom=true, toAnywhere=false. Reply is a full ordered list; fixed middle cards stay fixed. |
| [prompts/chooseAbility.json](prompts/chooseAbility.json) | [prompts/chooseAbility.reply.json](prompts/chooseAbility.reply.json) | Normal selectCard on Selesnya Guildmage, two activated abilities; reply is one integer optionId. The cost Input is subsequently cancelled. |

**Destination limitation:** scry supplies a null initial destination but uses the
dual-list *destination selection* semantics (`remainingMax > 0`). The codec emits
`destination: []` for both a null and an empty initial list; no wire flag distinguishes
them. A **nonempty pre-populated** destination is used by
PlayerControllerHuman.chooseContraptionsToCrank (`PlayerControllerHuman.java:513-543`):
previously cranked Contraptions start in the destination. The configured Commander
game has no Contraption deck/assemble scenario, so this catalog does not pretend to
capture that additional initial condition. Both whole-list ordering and partial
destination replies are captured and accepted.

## Input state files

Combat/mana fixtures reuse the unmodified `CombatAndManaStateTest.runScenario(true)`
(the ActiveInputTest scenario), including its Forge-thread guard and live-state
comparisons. All other Inputs below are created by the real human controller in
the second started game, answered via socket actions after the displayed state
arrives. The capture prefers a completed prompt/buttons display over intermediate
setSelection updates.

| State file | Scenario / UI |
| --- | --- |
| [inputs/priority.json](inputs/priority.json) | Human main-phase priority; cards/highlights and labeled buttons. |
| [inputs/declareAttackers.json](inputs/declareAttackers.json) | 1.4a combat, two creatures and three opponents; attackTargets and chosen defender. |
| [inputs/declareBlockers.json](inputs/declareBlockers.json) | 1.4a combat, opposing Dreadmaws attacking seat 0; chosen attacker and blockers. |
| [inputs/target.json](inputs/target.json) | Human `chooseTargetsFor` using Shock's script; waits for this socket state before selectPlayer answers (the faster 1.4a targeting display can be coalesced away). |
| [inputs/payMana.json](inputs/payMana.json) | 1.4a manual payment with Forests/Birds; manaPoolOpen, strength-2 auto-tap preview, and structured remainingCost/xCost (task 2.7-prep). remainingCost uses Forge's brace notation; no English parsing is needed. |
| [inputs/selectCards.json](inputs/selectCards.json) | Human `choosePermanentsToSacrifice` from two live bears; InputSelectCardsFromList, selectCard answers. Probe selects but does not itself sacrifice. |
| [inputs/selectEntities.json](inputs/selectEntities.json) | Human `chooseSingleEntityForEffect` among live players (or the game's starting-player choice); selectPlayer answers. |
| [inputs/convoke.json](inputs/convoke.json) | Human `chooseCardsForConvokeOrImprovise` for Sprout Swarm's real cost with two live bears; OK elects to convoke zero creatures. |
| [inputs/selectStack.json](inputs/selectStack.json) | Opt placed on the real stack (EventPumpTest setup pattern), then Counterspell's actual targeting method; InputSelectStackItems created by TargetSelection; selectStackItem answers. |
| [inputs/confirm.json](inputs/confirm.json) | Human `confirmAction` using the desktop InputConfirm path; server's OK/Cancel labels answer. |
| [inputs/mulligan.json](inputs/mulligan.json) | Real Commander initial hand / InputConfirmMulligan before puzzle application. |
| [inputs/waiting.json](inputs/waiting.json) | 1.4a human waits while an AI acts; no stale actions. |

## Unavailable live kinds (explicit coverage exceptions)

- `prompt.option`: ProtocolGuiGame.showOptionDialog implements the protocol entry,
  but there is no caller on a match's IGuiGame in the game engine or human controller.
  The calls in SOptionPane/updater/deck screens go through the global GuiBase
  interface, not this seat's protocol GUI. No real seat-0 Commander wire example
  exists with the current configuration. PromptCodecTest still covers its codec.
- `prompt.text`: BasicMacroSystem.setRememberedActions would call the nonnumeric
  showInputDialog (`BasicMacroSystem.java:77`), but PlayerControllerHuman.macros
  (`PlayerControllerHuman.java:3762-3766`) always constructs RecordActionsMacroSystem;
  the BasicMacroSystem constructor is commented out. The active recorder's only
  showInputDialog (`RecordActionsMacroSystem.java:355`) is numeric. No live card
  effect calls a free-text seat dialog. No synthetic text fixture is supplied.
- `activeInput.select`: the tracker fallback is for unknown classes. All concrete
  live Commander Input classes in forge-gui's match/input package map to specific
  kinds. The only remaining concrete class is InputPlaybackControl, constructed by
  FControlGamePlayback (`FControlGamePlayback.java:41`) for replay, not a live
  Commander human. ActiveInputTrackerTest tests the fallback with an artificial
  class, which is deliberately not promoted to a real-game wire fixture here.

The test compares coverage with the schema enums and fails for any new kind,
missing available kind, missing variant, missing/unaccepted reply, invalid JSON or
extra/stale fixture file. These three exceptions must be revisited if their real
callers/configuration change; they are not blanket permission to synthesize data.
