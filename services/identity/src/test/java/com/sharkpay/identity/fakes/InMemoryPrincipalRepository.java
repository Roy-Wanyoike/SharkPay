package com.sharkpay.identity.fakes;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PrincipalRepository} fake. Mirrors the unique constraint
 * on shark_id: saving a principal whose sharkId is already taken by another
 * id throws.
 */
public final class InMemoryPrincipalRepository implements PrincipalRepository {

    private final Map<UUID, Principal> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> idBySharkId = new ConcurrentHashMap<>();

    @Override
    public Principal save(Principal principal) {
        UUID existing = idBySharkId.get(principal.sharkId().value());
        if (existing != null && !existing.equals(principal.id())) {
            throw new IllegalStateException("duplicate shark_id " + principal.sharkId());
        }
        byId.put(principal.id(), principal);
        idBySharkId.put(principal.sharkId().value(), principal.id());
        return principal;
    }

    @Override
    public Optional<Principal> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Principal> findBySharkId(SharkId sharkId) {
        return Optional.ofNullable(idBySharkId.get(sharkId.value())).map(byId::get);
    }

    public int count() {
        return byId.size();
    }

    public Collection<Principal> all() {
        return byId.values();
    }
}
