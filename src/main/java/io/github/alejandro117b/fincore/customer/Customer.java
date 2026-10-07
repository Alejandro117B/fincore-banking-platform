package io.github.alejandro117b.fincore.customer;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "customers")
public class Customer {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 150)
    private String lastName;

    @Column(length = 254)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Customer() {
        // Required by JPA.
    }

    private Customer(String firstName, String lastName, String email, Instant at) {
        this.firstName = validateName(firstName, "firstName", 100);
        this.lastName = validateName(lastName, "lastName", 150);
        this.email = validateEmail(email);
        if (at == null) {
            throw new IllegalArgumentException("Creation time is required");
        }
        this.id = UUID.randomUUID();
        this.createdAt = at;
        this.updatedAt = at;
    }

    public static Customer create(String firstName, String lastName, String email, Instant at) {
        return new Customer(firstName, lastName, email, at);
    }

    public void updateContactDetails(String firstName, String lastName, String email, Instant at) {
        String validatedFirstName = validateName(firstName, "firstName", 100);
        String validatedLastName = validateName(lastName, "lastName", 150);
        String validatedEmail = validateEmail(email);
        if (at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Update time must not precede the previous update");
        }
        this.firstName = validatedFirstName;
        this.lastName = validatedLastName;
        this.email = validatedEmail;
        this.updatedAt = at;
    }

    private static String validateName(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static String validateEmail(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 254 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Email must have a valid contact address format");
        }
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
