# Forge Arena rules smoke checklist — 2026-11-02

Plan section 9.2, for Phase 2.11 and every release. Will records the results below;
the automated startup test does **not** certify these Unity interactions.

## Start and reset

Use IntelliJ **Smoke-1** through **Smoke-9** (the descriptive suffix names the
scenario). Each starts ArenaServerMain with humans in wire seats **0 and 1**, bots
in 2 and 3, `127.0.0.1:36800`, meter off, and the matching puzzle. The server's
working directory is `forge-arena-server/`. All puzzles start on **seat 0's first
main phase, turn 7**, with untapped mana and no summoning sickness. Their boards
replace the synthetic starting decks; these are rules/UI setups, not deck-legality tests.

Connect the Unity client to the local server and use its seat switcher for 0/1.
**Restart the server/puzzle for each checklist row** unless the row explicitly says
to continue a sequence. Rejoin a restarted server with fresh seat credentials if
needed; preserve the existing token for the reconnect procedure within one game.
Stop the previous run before starting another (all use port 36800).

In the steps below **resolve** means pass priority / press the server's labeled
OK button on **both human seats** as each is offered a decision; bots pass for
themselves. Never press a generic combat Cancel: use the server's labeled Done /
No attackers / No blockers control. If an Input or prompt is missing, do not use
dev cheats to continue; mark Fail and retain the server transcript plus client log.
For ordering/pile prompts leave **Remember** unchecked so repeat checks really prompt.

| Section 9.2 item | Puzzle file | Seat(s) acting | Exact Unity steps | Expected result | Pass/Fail | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Modal spell (choose modes) | [01-modes-x.pzl](01-modes-x.pzl) | 0; 1 passes | Cast **Abzan Charm**; choose **Exile target creature with power 3 or greater**; select seat 1's **Colossal Dreadmaw**; pay W/B/G and resolve. | Mode selection and targeting complete; Dreadmaw moves battlefield → exile; Charm goes to graveyard. | | |
| X spell | [01-modes-x.pzl](01-modes-x.pzl) | 0; 1 passes | Cast **Fireball**; in the **choose** prompt titled **Choose X for Fireball**, select the option labeled **3** and confirm; select only player 1, then finish targets; pay **3R** and resolve. Do not add extra targets (they add cost). | Finite X choices arrive as prompt.kind=choose (not number/text). Player 1 goes 40 → 37; the chosen X is used, not the number of targets. | | |
| Cultivate: search and split | [02-search-draw-piles.pzl](02-search-draw-piles.pzl) | 0; 1 passes | Cast **Cultivate**, pay 2G, resolve; search for **Forest** and **Island**; choose Forest for the battlefield and Island for hand; acknowledge reveal/order prompts as offered. | Forest enters **tapped**, Island is in seat 0's hand, library has two fewer cards and is shuffled. Opponents do not see the remaining library. | | |
| Scry and Surveil (list manipulation) | [03-scry-surveil-triggers.pzl](03-scry-surveil-triggers.pzl) | 0; 1 passes | Cast **Preordain** (U). Scry the known top Forest/Island: send Forest to the bottom and keep Island on top; finish and draw Island. **Restart**. Cast **Notion Rain** (1UB): put Forest in the graveyard, keep Island on top; finish the displayed ordering and draw two. | Scry changes top/bottom without milling; Preordain draws Island. Surveil mills Forest; Notion Rain draws Island and Swamp and deals 2 to seat 0 (40 → 38). Forge's Surveil can use destination/order prompts instead of Scry's manipulate-list widget. | | |
| Brainstorm | [02-search-draw-piles.pzl](02-search-draw-piles.pzl) | 0; 1 passes | Cast **Brainstorm**, pay U, resolve; draw Forest/Island/Swamp; choose Forest and Island from hand to return; order **Island on top, Forest next**. | Three cards drawn and exactly two returned in that order; net hand size unchanged from before casting; next draw is Island. | | |
| Sweeper | [04-sweeper.pzl](04-sweeper.pzl) | 0; 1 passes | Cast **Wrath of God**, pay 2WW, resolve with both humans passing. | Every creature on all four boards is destroyed, including the bots' Walls; lands remain. Commanders begin in command zones and are unaffected. | | |
| Counterspell | [05-counterspell.pzl](05-counterspell.pzl) | 1 → 0 | The puzzle already has seat 1's **Brainstorm** on the stack. Switch to **seat 1 and pass its caster priority first**. Switch to 0; cast **Counterspell**, select Brainstorm's stack item, pay UU, and resolve. | Counterspell counters Brainstorm; both go to their owners' graveyards; seat 1 draws no cards. | | |
| Fact or Fiction (piles) | [02-search-draw-piles.pzl](02-search-draw-piles.pzl) | 0 → 1 → 0 | Cast **Fact or Fiction** (3U), resolve. When asked for the opponent, choose **seat 1**. Switch to 1; in **activeInput.kind=selectCards**, select Forest/Island for one pile, leaving Swamp/Mountain/Plains in the other; finish with the server's labeled button. Switch to 0 and choose the two-card pile. | Exactly Forest/Island go to seat 0's hand; the other three go to its graveyard. The separator's live selection belongs only to seat 1 (no prompt/requestId); the pile-choice prompt belongs only to 0. | | |
| Trample damage assignment | [06-combat-command-damage.pzl](06-combat-command-damage.pzl) | 0 → 1 → 0 | Advance to attackers; attack player 1 with **Colossal Dreadmaw only**. On 1, block with **Wall of Wood and Grizzly Bears**. On 0, use the **assignCombatDamage** panel to assign **3 to Wall, 2 to Bear, 1 to player 1**; finish damage and resolve. There is no damage-assignment-order prompt. | Both blockers die; Dreadmaw survives with 2 marked damage; player 1 goes 22 → 21. Allocation sums to six; both blockers receive lethal before any damage tramples to the player. | | |
| Two blockers: free damage assignment (Foundations rules) | [06-combat-command-damage.pzl](06-combat-command-damage.pzl) | 0 → 1 → 0 | Repeat the Dreadmaw attack/two blocks. In the **assignCombatDamage** panel (overrideOrder=true), give the **second blocker shown** lethal damage first, give the other blocker **0**, and assign all six to that second blocker; assign **0** to the player. Submit. **Restart** and check another legal split: Wall=3, Bear=2, player=1. | No blocker ordering step exists. Both legal splits are accepted regardless of panel order: six to one blocker kills only that blocker; player stays 22. The 3/2/1 split kills both blockers and puts player 1 at 21. Assigning player damage still requires lethal on every blocker. | | |
| Simultaneous triggers to order | [03-scry-surveil-triggers.pzl](03-scry-surveil-triggers.pzl) | 0; 1 passes | Cast **Grizzly Bears** (1G), resolve. At the simultaneous-trigger ordering prompt, place **Soul Warden's gain-life trigger first to resolve**, then **Impact Tremors**; leave Remember off; confirm and resolve each trigger. | Two distinct triggers can be reordered. Seat 0 gains 1 (40 → 41); every opponent loses 1 (40 → 39). No extra setup triggers should already be on the stack. | | |
| Planeswalker attack | [06-combat-command-damage.pzl](06-combat-command-damage.pzl) | 0 → 1 | Advance to attackers. Set **Jace Beleren** as defender for **Grizzly Bears only** (not player 1); finish attackers. Switch to 1 and choose no blockers. Resolve combat. | Attack arrow/attackTarget ends at Jace; loyalty goes **5 → 3**; player 1 remains at 22 life. | | |
| Monarch | [09-monarch-initiative.pzl](09-monarch-initiative.pzl) | 0; 1 passes | Cast **Palace Jailer** (2WW), choose seat 1's **Grizzly Bears** for its exile trigger; order/resolve both ETB triggers. Advance through seat 0's end step. | Seat 0 becomes Monarch, Bears is exiled, and seat 0 draws one at its end step. Monarch is a player designation, not a card copied into opponents' hands. | | |
| Initiative (Undercity) | [09-monarch-initiative.pzl](09-monarch-initiative.pzl) | 0; 1 passes | Cast **White Plume Adventurer** (2W) and resolve its ETB trigger. Enter **Undercity**; resolve **Secret Entrance**, choosing a Forest for hand. Then pass through a complete turn cycle without attacking or transferring initiative. At seat 0's next upkeep choose **Lost Well** and scry two cards. | Seat 0 gets Initiative and Undercity; first room searches/reveals a basic and shuffles. The next upkeep offers the legal branch (Forge / Lost Well), and Lost Well's Scry completes. Branch testing requires advancing turns; no dungeon room is injected by the puzzle. | | |
| Commander tax recast | [07-tax-sacrifice.pzl](07-tax-sacrifice.pzl) | 0; 1 passes | Cast **Isamaru, Hound of Konda** from command for W and resolve. Activate **Viscera Seer**, sacrifice Isamaru as its cost; choose Command if a commander-move prompt appears; finish Scry. Cast Isamaru from command again. | After first cast, casts=1/tax=2. The recast costs **2W**; after it is cast, casts=2/tax=4. Sacrifice/zone changes do not reset cast history. No starting cast count is fabricated. | | |
| Commander damage kill | [06-combat-command-damage.pzl](06-combat-command-damage.pzl) | 0 → 1 | Attack player 1 with **Isamaru only** (21/21, 19 +1/+1 counters, marked as commander). On 1 choose **no blockers**. Resolve damage. Do not also send Dreadmaw/Bears. | Player 1 takes 21 from that commander and is eliminated with **1 life remaining** (22 − 21); seats 0, 2 and 3 continue. Commander-damage display records the hit. Prior 20 damage cannot be encoded; this uses a single 21-point hit instead. | | |
| Clone effect | [08-clone-attachments.pzl](08-clone-attachments.pzl) | 0; 1 passes | Cast **Clone** (3U), resolve; accept the optional copy effect and choose seat 1's **Colossal Dreadmaw**. | Seat 0 gets a 6/6 Dreadmaw copy with trample, controlled/owned by seat 0; seat 1's original remains. The Clone is not a commander. | | |
| Equipment and aura | [08-clone-attachments.pzl](08-clone-attachments.pzl) | 0; 1 passes | Initially Bonesplitter and Rancor are on Grizzly Bears (6/2). Activate **Bonesplitter's Equip** for 1, target **Runeclaw Bear**, resolve; check both cards. Then cast the **Rancor in hand** (G) onto Runeclaw Bear and resolve. | Equipment moves to Runeclaw: Grizzly becomes 4/2 with its original Rancor; Runeclaw becomes 4/2. New aura then makes Runeclaw **6/2 with trample**. attachedTo and visuals identify the correct bear. | | |
| Sacrifice-as-cost ability | [07-tax-sacrifice.pzl](07-tax-sacrifice.pzl) | 0; 1 passes | Activate **Viscera Seer** and select **Grizzly Bears** as the sacrifice. Before passing priority to resolve Scry, inspect the battlefield/graveyard; then finish the top/bottom choice for Scry 1. | Bears is already in the graveyard while the Scry ability waits on the stack: sacrifice is a cost, not a resolution effect. The selection and Scry prompts both finish. | | |
| Choose a player across three opponents | [02-search-draw-piles.pzl](02-search-draw-piles.pzl) | 0; 1 passes | Cast **Secret Rendezvous** (1WW). Confirm all three opponent players are selectable; choose **seat 3**, finish targets, pay and resolve. | Only seats 0 and 3 draw three; 1 and 2 do not. Player selection uses the actual player id, not a card/seat index. | | |
| Concession mid-game (procedure) | [01-modes-x.pzl](01-modes-x.pzl) | 1, then 0 | With the game parked at normal priority (no prompt open), switch to seat 1; press Concede and accept its confirmation. Switch back to 0, cast Fireball for X=1 targeting player 2 and resolve. | Seat 1 is eliminated; the other three continue and can complete the next action. A rejoined seat 1 is a viewer and cannot submit game actions. | | |
| Reconnect mid-selection (procedure) | [02-search-draw-piles.pzl](02-search-draw-piles.pzl) | 0 → 1 → 0 | Cast Fact or Fiction; choose seat 1 as separator. On 1, in **activeInput.kind=selectCards**, select Forest for a pile but do not finish. Record activeInput.chosen; disconnect the client's transport (or temporarily disable networking) without conceding/closing the server. Rejoin seat 1 using its **same stored token**. Verify the same selection reappears with Forest still chosen; finish the Forest/Island pile, then switch to 0 and choose a pile. | The game waits; hello → join/joined → logTail → cached state restores the selection, followed by a fresh safe state. The selection is an activeInput, with **no requestId** and no promptReply. Finishing it resumes Fact or Fiction once, with no duplicated draws or zone changes. | | |
| Reconnect mid-prompt (procedure) | [01-modes-x.pzl](01-modes-x.pzl) | 0; 1 passes | Cast Abzan Charm and leave its **choose** mode prompt open. Record its **requestId**; disconnect seat 0's transport without conceding/closing the server. Rejoin 0 with its **same stored token**. Verify the prompt reopens with the **same requestId**. Choose the exile mode, target seat 1's Colossal Dreadmaw, pay W/B/G and resolve. | After hello → join/joined → logTail → cached state, the pending real prompt is replayed with its original requestId. One accepted promptReply completes it; the game resumes and exiles Dreadmaw once. | | |

## Staging and test boundaries

- `GameState.parseLine` supports p0..p3 zones, life/counters, active player/phase,
  turn and summoning-sickness removal. Cards use native IsCommander, Counters,
  Id/AttachedTo, Tapped and NoETBTrigs. ArenaPuzzle's existing `#arena-stack` creates
  seat 1's real Brainstorm stack entry for Smoke-5; no new loader keys/extensions
  or production code are added. `#smoke-highlight` / `#smoke-attackers` are comments
  interpreted **only by the test**.
- Neither native GameState nor ArenaPuzzle has a prior commander-damage or cast
  count key. Smoke-6 uses 21-power Isamaru versus 22 life (a low-life setup that
  still distinguishes commander loss from ordinary lethal damage). Smoke-7 begins
  at zero casts and builds tax through the actual first cast and sacrifice.
- Monarch/Initiative and an already-entered Undercity room cannot be seeded by the
  supported keys. Palace Jailer / White Plume Adventurer reach them by one real
  cast. Testing an Undercity branch then needs the next upkeep (or another real
  initiative transfer). There is no claim that a later dungeon room is preloaded.
- Two different trigger effects avoid Forge's intentional identical-trigger
  ordering shortcut: Soul Warden gains life; Impact Tremors damages opponents.
- Forge defaults `UI_SELECT_FROM_CARD_DISPLAYS` to **true** in
  `forge-gui/src/main/java/forge/localinstance/properties/ForgePreferences.java:166`.
  ArenaServerMain does **not** override it: this is the shared Forge preference,
  not a new Arena setting. With that preference on and the headless GUI's desktop
  path, `PlayerControllerHuman.useSelectCardsInput` uses InputSelectCardsFromList
  for Fact or Fiction's library-card pile selection (`TwoPilesEffect.resolve`).
  It is a live activeInput, not a blocking prompt. A user preference file can
  override the Forge default, so verify it is on for this selection procedure.
- Finite `PlayerControllerHuman.chooseNumber` choices use IGuiGame.one/getChoices:
  Fireball's X announcement is a **choose** prompt. Combat defaults to Foundations
  rules (`GameRules.orderCombatants=false`); Combat skips blocker ordering and
  passes overrideOrder=true to assignCombatDamage. Lethal before trample still applies.
- Automated `SmokePuzzleTest` starts all nine games with **two real human protocol
  GUIs/sockets + two AI seats**, checks zone contents (including library), life,
  counters, attachments, commanders, turn/phase and any preloaded stack; checks
  the requested legal-action highlights and the run-config paths. Immutable
  Commander Effect rules objects are excluded from comparisons with listed cards.
  Combat startup only advances to the attackers Input to inspect attacker
  highlights and Jace as a defender. It declares no attackers or blockers, casts
  no scenario spells, and concedes in normal harness cleanup. It also constructs
  Undercity from its real token script without entering any room.
  During startup/progression it passes seat 1's priority windows through the socket;
  Forge's MagicStack.add gives a staged spell's caster priority, independently of
  activeplayer. The counterspell test asserts Brainstorm is still on the stack.
- All pzl card names and scripts below are loaded/verified by that test. Client
  steps/results above are **manual expectations**, not claims that the test played
  them. If Unity does not yet expose an interaction (notably dungeon progress),
  mark it Fail/blocked in Notes; do not work around it through the raw browser page.

Run the startup checks alone from the repository root:

```powershell
mvn --% -pl forge-arena-server -am test -Dtest=SmokePuzzleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Run the full suite:

```powershell
mvn --% -pl forge-arena-server -am test -Dtest=forge.arena.server.**.*Test -Dsurefire.failIfNoSpecifiedTests=false
```

## Verified Forge script sources

Paths are relative to the repository root. Every listed card is also checked
against the live common-card database; Undercity is checked in the token database.

| Card | Forge source file |
| --- | --- |
| Abzan Charm | [forge-gui/res/cardsfolder/a/abzan_charm.txt](../../../../forge-gui/res/cardsfolder/a/abzan_charm.txt) |
| Bonesplitter | [forge-gui/res/cardsfolder/b/bonesplitter.txt](../../../../forge-gui/res/cardsfolder/b/bonesplitter.txt) |
| Brainstorm | [forge-gui/res/cardsfolder/b/brainstorm.txt](../../../../forge-gui/res/cardsfolder/b/brainstorm.txt) |
| Clone | [forge-gui/res/cardsfolder/c/clone.txt](../../../../forge-gui/res/cardsfolder/c/clone.txt) |
| Colossal Dreadmaw | [forge-gui/res/cardsfolder/c/colossal_dreadmaw.txt](../../../../forge-gui/res/cardsfolder/c/colossal_dreadmaw.txt) |
| Counterspell | [forge-gui/res/cardsfolder/c/counterspell.txt](../../../../forge-gui/res/cardsfolder/c/counterspell.txt) |
| Cultivate | [forge-gui/res/cardsfolder/c/cultivate.txt](../../../../forge-gui/res/cardsfolder/c/cultivate.txt) |
| Fact or Fiction | [forge-gui/res/cardsfolder/f/fact_or_fiction.txt](../../../../forge-gui/res/cardsfolder/f/fact_or_fiction.txt) |
| Fireball | [forge-gui/res/cardsfolder/f/fireball.txt](../../../../forge-gui/res/cardsfolder/f/fireball.txt) |
| Forest | [forge-gui/res/cardsfolder/f/forest.txt](../../../../forge-gui/res/cardsfolder/f/forest.txt) |
| Grizzly Bears | [forge-gui/res/cardsfolder/g/grizzly_bears.txt](../../../../forge-gui/res/cardsfolder/g/grizzly_bears.txt) |
| Impact Tremors | [forge-gui/res/cardsfolder/i/impact_tremors.txt](../../../../forge-gui/res/cardsfolder/i/impact_tremors.txt) |
| Isamaru, Hound of Konda | [forge-gui/res/cardsfolder/i/isamaru_hound_of_konda.txt](../../../../forge-gui/res/cardsfolder/i/isamaru_hound_of_konda.txt) |
| Island | [forge-gui/res/cardsfolder/i/island.txt](../../../../forge-gui/res/cardsfolder/i/island.txt) |
| Jace Beleren | [forge-gui/res/cardsfolder/j/jace_beleren.txt](../../../../forge-gui/res/cardsfolder/j/jace_beleren.txt) |
| Kenrith, the Returned King | [forge-gui/res/cardsfolder/k/kenrith_the_returned_king.txt](../../../../forge-gui/res/cardsfolder/k/kenrith_the_returned_king.txt) |
| Mountain | [forge-gui/res/cardsfolder/m/mountain.txt](../../../../forge-gui/res/cardsfolder/m/mountain.txt) |
| Notion Rain | [forge-gui/res/cardsfolder/n/notion_rain.txt](../../../../forge-gui/res/cardsfolder/n/notion_rain.txt) |
| Palace Jailer | [forge-gui/res/cardsfolder/p/palace_jailer.txt](../../../../forge-gui/res/cardsfolder/p/palace_jailer.txt) |
| Plains | [forge-gui/res/cardsfolder/p/plains.txt](../../../../forge-gui/res/cardsfolder/p/plains.txt) |
| Preordain | [forge-gui/res/cardsfolder/p/preordain.txt](../../../../forge-gui/res/cardsfolder/p/preordain.txt) |
| Rancor | [forge-gui/res/cardsfolder/r/rancor.txt](../../../../forge-gui/res/cardsfolder/r/rancor.txt) |
| Runeclaw Bear | [forge-gui/res/cardsfolder/r/runeclaw_bear.txt](../../../../forge-gui/res/cardsfolder/r/runeclaw_bear.txt) |
| Secret Rendezvous | [forge-gui/res/cardsfolder/s/secret_rendezvous.txt](../../../../forge-gui/res/cardsfolder/s/secret_rendezvous.txt) |
| Soul Warden | [forge-gui/res/cardsfolder/s/soul_warden.txt](../../../../forge-gui/res/cardsfolder/s/soul_warden.txt) |
| Swamp | [forge-gui/res/cardsfolder/s/swamp.txt](../../../../forge-gui/res/cardsfolder/s/swamp.txt) |
| Viscera Seer | [forge-gui/res/cardsfolder/v/viscera_seer.txt](../../../../forge-gui/res/cardsfolder/v/viscera_seer.txt) |
| Wall of Wood | [forge-gui/res/cardsfolder/w/wall_of_wood.txt](../../../../forge-gui/res/cardsfolder/w/wall_of_wood.txt) |
| White Plume Adventurer | [forge-gui/res/cardsfolder/w/white_plume_adventurer.txt](../../../../forge-gui/res/cardsfolder/w/white_plume_adventurer.txt) |
| Wrath of God | [forge-gui/res/cardsfolder/w/wrath_of_god.txt](../../../../forge-gui/res/cardsfolder/w/wrath_of_god.txt) |
| Undercity (dungeon token) | [forge-gui/res/tokenscripts/undercity.txt](../../../../forge-gui/res/tokenscripts/undercity.txt) |
