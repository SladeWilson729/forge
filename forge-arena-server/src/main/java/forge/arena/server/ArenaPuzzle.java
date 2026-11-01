package forge.arena.server;

import forge.game.*;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** GameState plus explicit Arena comments for multiplayer combat/stack restoration. */
public final class ArenaPuzzle {
    private final List<String> lines;
    private ArenaPuzzle(List<String> lines) { this.lines = lines; }
    public static ArenaPuzzle read(Path path) throws IOException {
        return new ArenaPuzzle(Files.readAllLines(path).stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
    }
    public void apply(Game game) { apply(game, game.getPlayers().stream().map(Player::getRegisteredPlayer).toList()); }
    /** Game thread only, before mainGameLoop, exactly like Forge's puzzle start hook. */
    public void apply(Game game, List<RegisteredPlayer> seats) {
        if (!forge.util.ThreadUtil.isGameThread()) throw new IllegalStateException("Apply puzzles on the game thread");
        List<Player> players = seats.stream().map(p -> ArenaServerMain.playerFor(game, p)).toList();
        List<String> nativeLines = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            if (line.matches("p[0-3].*")) {
                int seat = line.charAt(1) - '0';
                line = "p" + game.getPlayers().indexOf(players.get(seat)) + line.substring(2);
            } else if (line.matches("activeplayer=p[0-3]")) {
                line = "activeplayer=p" + game.getPlayers().indexOf(players.get(line.charAt(line.length() - 1) - '0'));
            }
            nativeLines.add(line);
        }
        GameState state = new GameState(); state.parse(nativeLines); state.applyToGame(game);
        for (String line : lines) if (line.startsWith("#arena-phase=")) {
            String[] values = line.substring(13).split(",");
            Player active = players.get(Integer.parseInt(values[1]));
            game.getPhaseHandler().devModeSet(PhaseType.valueOf(values[0]), active, Integer.parseInt(values[2]));
            game.getPhaseHandler().setCombat(new Combat(active));
        }
        for (String line : lines) if (line.startsWith("#arena-attack=")) {
            String[] v = line.substring(14).split("\\|");
            game.getCombat().addAttacker(card(players, v[0], v[1]), players.get(Integer.parseInt(v[2])));
        } else if (line.startsWith("#arena-block=")) {
            String[] v = line.substring(13).split("\\|");
            game.getCombat().addBlocker(card(players, v[0], v[1]), card(players, v[2], v[3]));
        } else if (line.startsWith("#arena-stack=")) {
            String[] v = line.substring(13).split("\\|", 2);
            Player player = players.get(Integer.parseInt(v[0]));
            var paper = forge.model.FModel.getMagicDb().getCommonCards().getCard(v[1]);
            if (paper == null) throw new IllegalArgumentException("Unknown stack card: " + v[1]);
            Card card = Card.fromPaperCard(paper, player); card.setGameTimestamp(game.getNextTimestamp());
            player.getZone(ZoneType.Hand).add(card);
            var ability = card.getFirstSpellAbility(); ability.setActivatingPlayer(player);
            card = game.getAction().moveToStack(card, ability); ability.setHostCard(card); game.getStack().add(ability);
        }
        if (game.getCombat() != null) game.updateCombatForView();
        // GameState/devModeSet changes PhaseHandler.turn without refreshing the
        // tracked view. A real start already cached turn 1 before the puzzle hook.
        game.updateTurnForView();
    }
    private static Card card(List<Player> players, String seat, String name) {
        return players.get(Integer.parseInt(seat)).getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Puzzle battlefield card missing: " + name));
    }
}
