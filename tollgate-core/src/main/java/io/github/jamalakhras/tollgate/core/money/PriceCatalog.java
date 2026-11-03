package io.github.jamalakhras.tollgate.core.money;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class PriceCatalog {

    private static final String RESOURCE = "prices.tsv";
    private static final String VERSION_PREFIX = "# ";
    private static final Currency USD = Currency.getInstance("USD");

    private final String version;
    private final Map<String, Map<String, ModelPrice>> prices;

    private PriceCatalog(String version, Map<String, Map<String, ModelPrice>> prices) {
        this.version = version;
        this.prices = prices;
    }

    public static PriceCatalog bundled() {
        return Bundled.CATALOG;
    }

    public String version() {
        return version;
    }

    public boolean covers(String provider) {
        return prices.containsKey(provider);
    }

    public Optional<ModelPrice> price(String provider, String model) {
        return Optional.ofNullable(prices.getOrDefault(provider, Map.of()).get(model));
    }

    static PriceCatalog read(BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null || !first.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("The price catalog has no version line");
        }
        Map<String, Map<String, ModelPrice>> prices = new HashMap<>();
        Row base = null;
        List<ContextTier> tiers = new ArrayList<>();
        int number = 1;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            number++;
            Row row = Row.parse(line, number);
            if (row.fromTokens() == 0) {
                add(prices, base, tiers);
                base = row;
                tiers = new ArrayList<>();
            } else if (base != null && base.sameModel(row)) {
                tiers.add(new ContextTier(row.fromTokens(), row.rates()));
            } else {
                throw new IllegalStateException("Line " + number + " of the price catalog has no base price");
            }
        }
        add(prices, base, tiers);
        return new PriceCatalog(first.substring(VERSION_PREFIX.length()), prices);
    }

    private static void add(Map<String, Map<String, ModelPrice>> prices, @Nullable Row base, List<ContextTier> tiers) {
        if (base != null) {
            prices.computeIfAbsent(base.provider(), provider -> new HashMap<>())
                    .put(base.model(), new ModelPrice(base.rates(), tiers, null));
        }
    }

    private record Row(String provider, String model, long fromTokens, TokenRates rates) {

        static Row parse(String line, int number) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 8) {
                throw new IllegalStateException(
                        "Line " + number + " of the price catalog has " + fields.length + " fields, not 8");
            }
            try {
                return new Row(
                        fields[0],
                        fields[1],
                        Long.parseLong(fields[2]),
                        new TokenRates(
                                price(fields[3]),
                                price(fields[4]),
                                price(fields[5]),
                                price(fields[6]),
                                price(fields[7])));
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new IllegalStateException("Line " + number + " of the price catalog is not valid", e);
            }
        }

        boolean sameModel(Row other) {
            return provider.equals(other.provider) && model.equals(other.model);
        }

        private static TokenPrice price(String amount) {
            return new TokenPrice(Money.of(new BigDecimal(amount), USD));
        }
    }

    private static final class Bundled {

        static final PriceCatalog CATALOG = load();

        private static PriceCatalog load() {
            try (InputStream stream = Objects.requireNonNull(
                            PriceCatalog.class.getResourceAsStream(RESOURCE), "The price catalog is missing");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return read(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the price catalog", e);
            }
        }
    }
}
