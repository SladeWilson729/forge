package forge.arena.server.view;

import forge.game.card.CardView;
import forge.game.player.PlayerView;

/** One visibility decision shared by snapshot and payload filtering, on a Forge-safe thread. */
public record CardVisibility(boolean shown, boolean faceDown, boolean identityVisible) {
    public static CardVisibility forSeat(final CardView card, final PlayerView seat) {
        final boolean shown = card.canBeShownTo(seat);
        final boolean down = card.isFaceDown();
        // Both Forge predicates honor mayPlayerLook, including face-down exile permissions.
        return new CardVisibility(shown, down, shown && (!down || card.canFaceDownBeShownTo(seat)));
    }
}
