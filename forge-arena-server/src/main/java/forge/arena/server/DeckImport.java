package forge.arena.server;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckRecognizer;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.*;
import java.util.regex.Pattern;

/** Text import using Forge's recognizer and Commander rules, without mutating either. */
public final class DeckImport {
    private DeckImport() { }
    public record Problem(int line, String text, String message) { }
    private record Source(int line, String text) { }
    public record Result(Deck deck, boolean needsCommander, List<String> candidates,
                         List<Problem> errors, List<String> warnings, List<String> commander, int count, String deckName) {
        public boolean ok() { return !needsCommander && errors.isEmpty(); }
        public JsonObject json() {
            JsonObject out = new JsonObject(); out.addProperty("type", "deckResult"); out.addProperty("ok", ok());
            out.addProperty("needsCommander", needsCommander); out.add("candidates", WsServer.GSON.toJsonTree(candidates));
            out.add("errors", WsServer.GSON.toJsonTree(errors)); out.add("warnings", WsServer.GSON.toJsonTree(warnings));
            out.add("commander", WsServer.GSON.toJsonTree(commander)); out.addProperty("count", count);
            out.addProperty("deckName", deckName); return out;
        }
    }

    public static Result parse(String text, List<String> commanderOverride) {
        Deck deck = new Deck("Imported Commander deck");
        List<Problem> errors = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Map<String, Source> sources = new LinkedHashMap<>();
        DeckRecognizer recognizer = new DeckRecognizer(); recognizer.setDeckFormatConstraint(DeckFormat.Commander);
        recognizer.setAllowedDeckSections(new ArrayList<>(DeckFormat.Commander.getPrimaryDeckSections()));
        recognizer.forceImportBannedAndRestrictedCards();
        DeckSection section = DeckSection.Main;
        List<PaperCard> tagged = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i], line = raw.trim();
            if (line.isBlank()) continue;
            boolean commanderTag = Pattern.compile("(?i)\\[Commander[^]]*]").matcher(line).find();
            line = line.replaceAll("(?i)\\[Commander[^]]*]", "").trim();
            var heading = recognizer.recognizeLine(line, section);
            if (heading != null && heading.getType() == DeckRecognizer.TokenType.DECK_SECTION_NAME) {
                section = DeckSection.valueOf(heading.getText()); continue;
            }
            if (heading != null && heading.getType() == DeckRecognizer.TokenType.DECK_NAME) { deck.setName(heading.getText()); continue; }
            var tokens = recognizer.parseCardList(new String[] {section.name(), line});
            for (int j = 1; j < tokens.size(); j++) {
                var token = tokens.get(j);
                if (token.getType() == DeckRecognizer.TokenType.DECK_SECTION_NAME) {
                    continue;
                }
                if (token.getType() == DeckRecognizer.TokenType.DECK_NAME) { deck.setName(token.getText()); continue; }
                if (token.getType() == DeckRecognizer.TokenType.UNKNOWN_CARD || token.getType() == DeckRecognizer.TokenType.CARD_FROM_INVALID_SET) {
                    String fallback = line.replaceFirst("\\s+[\\[(][A-Za-z0-9_]{2,7}[\\])](?:\\s+\\S+)*$", "");
                    if (!fallback.equals(line)) {
                        var replacement = recognizer.recognizeLine(fallback, section);
                        if (replacement != null && replacement.getCard() != null) {
                            token = replacement;
                            warnings.add("Line " + (i + 1) + ": Set or printing not found; using another printing of " + token.getCard().getName() + ".");
                        }
                    }
                }
                PaperCard card = token.getCard();
                if (card != null) {
                    if (card.getRules().isUnsupported()) {
                        errors.add(new Problem(i + 1, raw, "Forge cannot play " + card.getName() + " yet.")); continue;
                    }
                    DeckSection destination = section == DeckSection.Commander ? section : token.getTokenSection() == null ? section : token.getTokenSection();
                    deck.getOrCreate(destination).add(card, token.getQuantity());
                    sources.putIfAbsent(card.getName(), new Source(i + 1, raw));
                    if (commanderTag) tagged.add(card);
                    if (FModel.getFormats().get("Commander").getBannedCardNames().contains(card.getName()))
                        warnings.add(card.getName() + " is banned in Commander; allowed by this server's house rule.");
                } else switch (token.getType()) {
                    case UNKNOWN_CARD -> errors.add(new Problem(i + 1, raw, "Unknown card: " + token.getText().replaceFirst("^\\d+x?\\s+", "") + "; check the spelling."));
                    case UNSUPPORTED_CARD -> errors.add(new Problem(i + 1, raw, "Forge cannot play " + token.getText().replaceFirst(" \\[.*]$", "") + " in a Commander deck."));
                    case UNKNOWN_TEXT, UNSUPPORTED_DECK_SECTION -> errors.add(new Problem(i + 1, raw, "Unrecognized deck text."));
                    default -> { }
                }
            }
        }
        List<String> chosen = new ArrayList<>();
        if (deck.getCommanders().isEmpty()) {
            if (!tagged.isEmpty()) chosen.addAll(tagged.stream().map(PaperCard::getName).toList());
            else if (commanderOverride != null) chosen.addAll(commanderOverride);
            for (String name : chosen) {
                PaperCard card = null;
                for (var entry : deck.getMain()) if (entry.getKey().getName().equalsIgnoreCase(name)) { card = entry.getKey(); break; }
                if (card == null) errors.add(new Problem(0, name, "Commander is not in the deck: " + name + "."));
                else { deck.getMain().remove(card, 1); deck.getOrCreate(DeckSection.Commander).add(card, 1); }
            }
        }
        List<String> commanders = deck.getCommanders().stream().map(PaperCard::getName).toList();
        List<String> candidates = new ArrayList<>();
        for (var entry : deck.getMain()) if (DeckFormat.Commander.isLegalCommander(entry.getKey().getRules())) candidates.add(entry.getKey().getName());
        int count = deck.getMain().countAll() + (deck.has(DeckSection.Commander) ? deck.get(DeckSection.Commander).countAll() : 0);
        boolean needs = commanders.isEmpty();
        if (!needs) {
            String commanderProblem = DeckFormat.Commander.getCommanderConformanceProblem(deck);
            if (commanderProblem != null) {
                String message = commanderProblem.startsWith("contains one or more cards")
                        ? "Cards outside the commander's color identity: " + commanderProblem.substring(commanderProblem.indexOf(':') + 1).trim().replace("\n", ", ") + "."
                        : commanderProblem.equals("has an illegal commander") ? "Illegal commander: " + String.join(", ", commanders) + "."
                        : "Commander problem: " + commanderProblem + ".";
                if (commanderProblem.startsWith("contains one or more cards")) {
                    for (String name : commanderProblem.substring(commanderProblem.indexOf(':') + 1).trim().split("\\R"))
                        addProblem(errors, sources, name, "Cards outside the commander's color identity: " + name + ".");
                } else addProblem(errors, sources, commanders.get(0), message);
            }
            if (count != 100) errors.add(new Problem(0, "", "Commander decks must contain exactly 100 cards including commanders; found " + count + "."));
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (var entry : deck.getAllCardsInASinglePool()) counts.merge(entry.getKey().getName(), entry.getValue(), Integer::sum);
            for (var entry : deck.getAllCardsInASinglePool()) {
                PaperCard card = entry.getKey(); int maximum = DeckFormat.Commander.getMaxCardCopies(card);
                if (counts.get(card.getName()) > maximum) {
                    addProblem(errors, sources, card.getName(), "Too many copies of " + card.getName() + "; Commander allows " + maximum + ".");
                    counts.put(card.getName(), 0);
                }
            }
            String full = DeckFormat.Commander.getDeckConformanceProblem(deck);
            if (full != null && errors.isEmpty()) errors.add(new Problem(0, "", "Deck problem: " + full.replace("\n", " ") + "."));
        }
        return new Result(errors.isEmpty() && !needs ? deck : null, needs, List.copyOf(candidates), List.copyOf(errors),
                List.copyOf(warnings), commanders, count, deck.getName());
    }

    private static void addProblem(List<Problem> errors, Map<String, Source> sources, String name, String message) {
        Source source = sources.getOrDefault(name, new Source(0, name));
        errors.add(new Problem(source.line(), source.text(), message));
    }
}
