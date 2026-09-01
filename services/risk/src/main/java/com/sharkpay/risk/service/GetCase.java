package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseIds;
import com.sharkpay.risk.domain.exceptions.CaseNotFoundException;
import com.sharkpay.risk.ports.CaseRepository;
import org.springframework.stereotype.Service;

/** Fetch a compliance case by id. */
@Service
public class GetCase {

    private final CaseRepository cases;

    public GetCase(CaseRepository cases) {
        this.cases = cases;
    }

    /** Accepts the public {@code case_<hex>} id or a bare UUID. */
    public Case get(String rawCaseId) {
        return cases.findById(CaseIds.parse(rawCaseId))
                .orElseThrow(() -> new CaseNotFoundException(rawCaseId));
    }
}
