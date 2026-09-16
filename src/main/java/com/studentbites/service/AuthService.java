package com.studentbites.service;

import com.studentbites.dto.SignupForm;
import com.studentbites.model.AppUser;
import com.studentbites.repository.AppUserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {
    public static final String USER_ID = "currentUserId";
    public static final String USER_NAME = "currentUserName";
    public static final String USER_EMAIL = "currentUserEmail";
    public static final String USER_PHONE = "currentUserPhone";
    public static final String USER_HOSTEL = "currentUserHostel";

    private final AppUserRepository users;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final Pbkdf2PasswordEncoder legacyPbkdf2 = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public AuthService(AppUserRepository users) {
        this.users = users;
    }

    public AppUser signup(SignupForm form) {
        String normalizedEmail = normalizeEmail(form.getEmail());
        if (users.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateEmailException();
        }
        AppUser user = new AppUser();
        user.setFullName(normalizeText(form.getFullName()));
        user.setEmail(normalizedEmail);
        user.setPhone(normalizeText(form.getPhone()));
        user.setHostelOrClass(normalizeText(form.getHostelOrClass()));
        user.setPasswordHash(hash(normalizePassword(form.getPassword())));
        return users.save(user);
    }

    public Optional<AppUser> login(String email, String password) {
        String rawPassword = password == null ? "" : password;
        String trimmedPassword = normalizePassword(rawPassword);
        return users.findByEmailIgnoreCase(normalizeEmail(email))
                .filter(user -> passwordMatches(user, rawPassword, trimmedPassword));
    }

    public void remember(AppUser user, HttpSession session) {
        session.setAttribute(USER_ID, user.getId());
        session.setAttribute(USER_NAME, user.getFullName());
        session.setAttribute(USER_EMAIL, user.getEmail());
        session.setAttribute(USER_PHONE, user.getPhone());
        session.setAttribute(USER_HOSTEL, user.getHostelOrClass());
    }

    public boolean loggedIn(HttpSession session) {
        return session.getAttribute(USER_EMAIL) != null;
    }

    public void logout(HttpSession session) {
        session.invalidate();
    }

    public String hash(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes");
        }
        return "{bcrypt}" + passwordEncoder.encode(password);
    }

    private String legacyHash(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(("studentbites:" + password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public boolean emailExists(String email) {
        return users.existsByEmailIgnoreCase(normalizeEmail(email));
    }

    private boolean passwordMatches(AppUser user, String rawPassword, String trimmedPassword) {
        String savedHash = user.getPasswordHash();
        if (savedHash.startsWith("{bcrypt}")) {
            return trimmedPassword.getBytes(StandardCharsets.UTF_8).length <= 72
                    && passwordEncoder.matches(trimmedPassword, savedHash.substring(8));
        }
        // Upgrade existing accounts only after successfully verifying their old password.
        boolean matches = savedHash.startsWith("{pbkdf2}")
                ? legacyPbkdf2.matches(trimmedPassword, savedHash.substring(8))
                : savedHash.equals(legacyHash(rawPassword)) || savedHash.equals(legacyHash(trimmedPassword));
        if (matches) {
            // Keep long legacy passwords usable without silently truncating them for BCrypt.
            if (trimmedPassword.getBytes(StandardCharsets.UTF_8).length <= 72) {
                user.setPasswordHash(hash(trimmedPassword));
                users.save(user);
            }
            return true;
        }
        return false;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizeText(String text) {
        return text == null ? null : text.trim();
    }

    private String normalizePassword(String password) {
        return password == null ? "" : password.trim();
    }

    public static class DuplicateEmailException extends RuntimeException {
    }
}
