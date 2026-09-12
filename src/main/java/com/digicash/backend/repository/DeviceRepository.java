package com.digicash.backend.repository;

import com.digicash.backend.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByPublicKeyFingerprint(String publicKeyFingerprint);

    boolean existsByPublicKeyFingerprint(String publicKeyFingerprint);
}
