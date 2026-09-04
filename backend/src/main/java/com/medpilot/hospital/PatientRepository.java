package com.medpilot.hospital;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByMpiIdAndActiveTrue(String mpiId);
}
