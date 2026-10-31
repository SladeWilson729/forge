# Yield preference audit (task 1.13)

Audited against Forge at commit 8caf5cd8fd. No Forge module is modified.

YC = `forge-gui/src/main/java/forge/gamemodes/match/YieldController.java`.
PCH = `forge-gui/src/main/java/forge/player/PlayerControllerHuman.java`.
IPP = `forge-gui/src/main/java/forge/gamemodes/match/input/InputPassPriority.java`.

| SYNCED_PREF | Classification | Runtime reads |
|---|---|---|
| YIELD_INTERRUPT_ON_ATTACKERS | per-seat | YC:545, getBoolPref |
| YIELD_INTERRUPT_ON_OPPONENT_SPELL | per-seat | YC:521, getBoolPref |
| YIELD_INTERRUPT_ON_TARGETING | per-seat | YC:526, getBoolPref |
| YIELD_INTERRUPT_ON_TRIGGERS | per-seat | YC:536, getBoolPref |
| YIELD_INTERRUPT_ON_REVEAL | per-seat | YC:495, getBoolPref |
| YIELD_INTERRUPT_ON_MASS_REMOVAL | per-seat | YC:531, getBoolPref |
| YIELD_AUTO_PASS_NO_ACTIONS | global-only | YC:555,561,616 honor overrides; static helpers YC:463,478 bypass them; IPP:95,242 and PCH:4063 honor overrides |
| YIELD_AUTO_PASS_RESPECTS_INTERRUPTS | per-seat | YC:555,561, getBoolPref |
| YIELD_SKIP_PHASE_DELAY | per-seat | PCH:1746, getBoolPref |
| YIELD_SKIP_RESOLVE_DELAY | per-seat | PCH:1749, getBoolPref |
| YIELD_SUPPRESS_ON_OWN_TURN | per-seat | IPP:224, getBoolPref |
| YIELD_SUPPRESS_AFTER_END | per-seat | IPP:89, getBoolPref |
| YIELD_AVAILABLE_ACTIONS_BUDGET_MS | per-seat | PCH:4071, getStringPref |
| YIELD_DECLINE_SCOPE_STACK_YIELD | per-seat | YC:574,589; IPP:101; PCH:4065, getDeclineScope |
| YIELD_DECLINE_SCOPE_NO_ACTIONS | per-seat | YC:589; IPP:102; PCH:4064, getDeclineScope |
| UI_SHOW_ACTIONABLE_HIGHLIGHTS | per-seat | PCH:1605,1660,1676,1710, getBoolPref |
| UI_SHOW_AUTOTAP_PREVIEW | per-seat | PCH:1607, getBoolPref |

The allow list is private at YC:52–71. The adapter copies all 17 names; a source-text coverage test verifies equality without reflection.

## Direct global reads

| Location | Read | Effect |
|---|---|---|
| YC:98 | FModel.getPreferences().getPref(pref) | Intended fallback only when no per-instance override exists. |
| YC:222 | UI_AUTO_DECISION_MODE | isAbilityScope: storage-key scope. Not a SYNCED_PREF. |
| YC:253 | UI_AUTO_DECISION_MODE | activeModeIsInstall: persistent-store routing. |
| YC:257 | UI_AUTO_DECISION_MODE | activeTier: GAME/MATCH/SESSION scope. |
| YC:342 | Every SYNCED_PREF | buildClientSnapshot exports the local client's global settings, not host per-seat overrides. The server constructs its own seed and does not call this export helper. |
| YC:462–463 | YIELD_AUTO_PASS_NO_ACTIONS | toggleAutoPassNoActions reads/flips/saves the global preference. |
| YC:478 | YIELD_AUTO_PASS_NO_ACTIONS | toggleAutoPassOrStopAll decides from the global preference. |
| IPP:316 | UI_MANA_LOST_PROMPT | Manual priority-pass mana-loss confirmation. Not a SYNCED_PREF. |
| PCH:2805 | UI_UNDO_MODE | Configures the undo checkpoint when a priority prompt opens. Not a SYNCED_PREF. |
| InputBase:166 | UI_SHOW_STORM_COUNT_IN_PROMPT | Prompt formatting only, not an auto-pass/interrupt decision; outside SYNCED_PREFS. |

`AutoYieldStore`, `PersistentAutoDecisionStore`, and `forge-ai/.../AvailableActions.java` contain no direct preference reads. Unrelated deck/order/display preference reads in PCH do not control these decisions.

Under the task's conservative “an override is ignored somewhere” definition, APINA is global-only even though the normal remote-host decision path honors its override. ArenaServerMain sets it to true, rejects per-seat changes, and permits only the matching global value in a client seed. UI_AUTO_DECISION_MODE stays at ForgeConstants.AUTO_DECISION_PER_ABILITY (Per Ability, Each Match); it cannot be changed through setYield. Other non-SYNCED preferences are rejected.

## Ownership and routing

PCH:124 creates one final `new YieldController(this)` per human player; PCH:153 exposes that instance. PCH:3919 recognizes ProtocolGuiGame as remote, so each human seat uses its own GAME cache and skip-phase map. The adapter finds the PCH through its RegisteredPlayer binding, not the sorted game-player index. Bot controllers and the playback spectator never receive a human seat's settings.

GameServerHandler:35 resolves the network slot's IGameController. NetGameController:182–184 mirrors locally and sends ProtocolMethod.sendYieldUpdate. IGameController:88 delegates that entry point to PCH.applyYieldUpdate (PCH:4005), which applies to its own YieldController and refreshes auto-pass. The adapter uses that entry point on the EDT. ProtocolGuiGame:312 forwards GUI applyYieldUpdate pushes back to the owner as yieldState. Since ordinary Forge clients mirror user updates locally, the JSON adapter echoes accepted records through that same GUI path before application can immediately cancel a yield; it also forwards automatic cancellations unchanged.

## Test isolation

The production server seeds APINA=true. PromptGameHarness scenarios explicitly use a server-wide APINA=false fixture and a client seed with every phase stop enabled and their existing preview settings. The separate CombatAndManaStateTest/ActiveInputTest fixtures retain APINA=true and leave a zero-cost Memnite in hand so their main-phase priority inspection has a legal action. They continue testing every original assertion. The two-human yield game instead uses production defaults and APINA=true. No test changes a global-only setting for just one seat.
