package com.medpilot.hospital;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientEncounterRepository extends JpaRepository<PatientEncounter, Long> {
    Optional<PatientEncounter> findByOrganizationCodeAndEncounterNumber(
            String organizationCode, String encounterNumber);
}
