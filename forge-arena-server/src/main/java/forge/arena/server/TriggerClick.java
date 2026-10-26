package forge.arena.server;

import forge.util.ITriggerEvent;

/** Desktop Input click semantics; coordinates have no meaning to the headless bridge. */
public record TriggerClick(int button) implements ITriggerEvent {
    public TriggerClick {
        if (button != 1 && button != 3) throw new IllegalArgumentException("Button must be 1 or 3");
    }
    public int getButton() { return button; }
    public int getX() { return 0; }
    public int getY() { return 0; }
}
