package forge.arena.server;

import org.testng.annotations.Test;

/** The same human/three-AI scenario, with Shock targeting and automatic-tap preview enabled. */
public class ActiveInputTest {
    @Test(timeOut = 180_000)
    public void liveKindsHighlightsAndTimerDisplay() throws Exception {
        final CombatAndManaStateTest scenario = new CombatAndManaStateTest();
        scenario.initialize();
        scenario.runScenario(true);
    }
}
