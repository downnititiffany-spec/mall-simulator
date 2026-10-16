package com.graduation.mall.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateUserReqContractTest {

    @Test
    void acceptsValuesFromCanonicalUserProfileEnums() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertTrue(validator.validate(new MallDtos.CreateUserReq("under18", "tier1", "normal")).isEmpty());
            assertTrue(validator.validate(new MallDtos.CreateUserReq("25-34", "tier2", "silver")).isEmpty());
            assertTrue(validator.validate(new MallDtos.CreateUserReq("45+", "other", "platinum")).isEmpty());
        }
    }

    @Test
    void rejectsValuesThatWouldBeQuarantinedByTheRegisteredMallMapping() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<String> paths = factory.getValidator()
                    .validate(new MallDtos.CreateUserReq("adult", "tier2", "basic"))
                    .stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("ageGroup", "memberLevel"), paths);
        }
    }

    @Test
    void stillRejectsBlankFields() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<String> paths = factory.getValidator()
                    .validate(new MallDtos.CreateUserReq(" ", "tier2", "silver"))
                    .stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());

            assertTrue(paths.contains("ageGroup"));
        }
    }
}
