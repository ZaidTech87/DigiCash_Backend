package com.digicash.backend.repository;

import com.digicash.backend.entity.Device;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceRepositoryTest {

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_andFindByFingerprint_returnsSameDevice() {
        Device device = new Device("3F:A1:9B:AA:BB:CC", "base64-public-key-data");
        deviceRepository.saveAndFlush(device);

        Optional<Device> found = deviceRepository.findByPublicKeyFingerprint("3F:A1:9B:AA:BB:CC");

        assertThat(found).isPresent();
        assertThat(found.get().getPublicKeyBase64()).isEqualTo("base64-public-key-data");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void duplicateFingerprint_violatesUniqueConstraint() {
        deviceRepository.saveAndFlush(new Device("DUP:FINGERPRINT", "key-one"));
        entityManager.clear();

        Device duplicate = new Device("DUP:FINGERPRINT", "key-two");

        assertThrows(DataIntegrityViolationException.class,
                () -> deviceRepository.saveAndFlush(duplicate));
    }

    @Test
    void existsByPublicKeyFingerprint_reflectsPresenceCorrectly() {
        deviceRepository.saveAndFlush(new Device("EXISTS:CHECK", "some-key"));

        assertThat(deviceRepository.existsByPublicKeyFingerprint("EXISTS:CHECK")).isTrue();
        assertThat(deviceRepository.existsByPublicKeyFingerprint("DOES:NOT:EXIST")).isFalse();
    }
}
