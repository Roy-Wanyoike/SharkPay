package com.sharkpay.fx.fakes;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.ports.ConversionRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory conversion repository (fake for tests and local dev wiring). */
public final class InMemoryConversionRepository implements ConversionRepository {

    private final Map<String, Conversion> store = new ConcurrentHashMap<>();

    @Override
    public Conversion save(Conversion conversion) {
        store.put(conversion.id(), conversion);
        return conversion;
    }

    @Override
    public Optional<Conversion> findById(String conversionId) {
        return Optional.ofNullable(store.get(conversionId));
    }

    @Override
    public List<Conversion> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(Conversion::createdAt).thenComparing(Conversion::id))
                .toList();
    }

    @Override
    public List<String> findInvolvedCurrencies() {
        return store.values().stream()
                .flatMap(conversion -> java.util.stream.Stream.of(
                        conversion.sourceAmount().currency(), conversion.targetAmount().currency()))
                .distinct()
                .sorted()
                .toList();
    }
}
