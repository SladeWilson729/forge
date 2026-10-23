package forge.arena.server;

import forge.GuiDesktop;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;

import java.util.List;

/** Desktop services without windows or audio; retains Forge's Swing EDT threading. */
public class HeadlessGui extends GuiDesktop {
    @Override
    public HostedMatch hostMatch() {
        return new HostedMatch();
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return new SeatProtocolGui(new LoggingRemote());
    }

    @Override
    public String getAssetsDir() {
        return System.getProperty("forge.assets", "../forge-gui/");
    }

    @Override
    public void showSpellShop() { }

    @Override
    public void showBazaar() { }

    @Override
    public int showOptionDialog(final String message, final String title,
                                final FSkinProp icon, final List<String> options,
                                final int defaultOption) {
        System.err.println("[HeadlessGui] " + title + ": " + message);
        // Dismiss rather than automatically approving a confirmation.
        return -1;
    }

    @Override
    public void showImageDialog(final ISkinImage image, final String message, final String title) {
        System.err.println("[HeadlessGui] " + title + ": " + message);
    }

    @Override
    public String showInputDialog(final String message, final String title,
                                  final FSkinProp icon, final String initialInput,
                                  final List<String> inputOptions, final boolean isNumeric) {
        System.err.println("[HeadlessGui] Input: " + title + ": " + message);
        if (initialInput != null) {
            return initialInput;
        }
        if (inputOptions != null && !inputOptions.isEmpty()) {
            return inputOptions.get(0);
        }
        return isNumeric ? "0" : "";
    }

    @Override
    public String showFileDialog(final String title, final String defaultDir) {
        System.err.println("[HeadlessGui] File dialog: " + title);
        return null;
    }

    @Override
    public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        System.err.println("[HeadlessGui] Bug Report - " + title + ":");
        System.err.println(text);
    }

    @Override
    public IAudioClip createAudioClip(final String filename) {
        return null;
    }

    @Override
    public IAudioMusic createAudioMusic(final String filename) {
        return null;
    }

    @Override
    public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
}
