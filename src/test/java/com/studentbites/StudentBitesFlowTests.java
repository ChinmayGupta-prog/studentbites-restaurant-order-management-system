package com.studentbites;

import com.studentbites.model.OrderStatus;
import com.studentbites.model.StudentOrder;
import com.studentbites.repository.StudentOrderRepository;
import com.studentbites.repository.AppUserRepository;
import com.studentbites.service.AuthService;
import com.studentbites.service.CartService;
import com.studentbites.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:studentbites-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class StudentBitesFlowTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StudentOrderRepository orders;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AuthService authService;

    private MockHttpSession studentSession(String email) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.USER_EMAIL, email);
        session.setAttribute(AuthService.USER_NAME, "Private Student");
        session.setAttribute(AuthService.USER_PHONE, "5555555555");
        return session;
    }

    @Test
    void invoicesAndTrackingRequireTheOrderOwner() throws Exception {
        MockHttpSession owner = studentSession("private@example.com");
        cartService.add(1L, owner);
        String invoice = mockMvc.perform(post("/checkout").session(owner)
                        .param("studentName", "Private Student").param("phone", "5555555555"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        String id = invoice.substring(invoice.lastIndexOf('/') + 1);
        mockMvc.perform(get(invoice)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/track").param("orderId", id)).andExpect(redirectedUrl("/login"));
        MockHttpSession stranger = studentSession("stranger@example.com");
        mockMvc.perform(get(invoice).session(stranger)).andExpect(redirectedUrl("/menu"));
        mockMvc.perform(get("/track").param("orderId", id).session(stranger))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeDoesNotExist("trackedOrder"));
        mockMvc.perform(get(invoice).session(owner)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("private@example.com")));
        mockMvc.perform(get("/track").param("orderId", id).session(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("trackedOrder"));
    }

    @Test
    void cartRejectsOverflowAndUnknownItemsAndSupportsRemoval() throws Exception {
        MockHttpSession session = new MockHttpSession();
        cartService.add(1L, session);
        for (String quantity : new String[]{"2147483647", "-1", "100", "2147483648"}) {
            mockMvc.perform(post("/cart/update").session(session).param("itemId", "1").param("quantity", quantity))
                    .andExpect(status().isBadRequest());
        }
        assertThat(cartService.count(session)).isEqualTo(1);
        cartService.update(1L, 99, session);
        mockMvc.perform(post("/cart/add/1").session(session)).andExpect(status().isBadRequest());
        assertThat(cartService.count(session)).isEqualTo(99);
        mockMvc.perform(post("/cart/update").session(session).param("itemId", "999999").param("quantity", "1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/cart/update").session(session).param("itemId", "1").param("quantity", "0"))
                .andExpect(status().is3xxRedirection());
        assertThat(cartService.count(session)).isZero();
    }

    @Test
    void invalidCheckoutModesDoNotSaveOrdersOrClearCart() throws Exception {
        MockHttpSession session = studentSession("invalid-modes@example.com");
        cartService.add(1L, session);
        for (String[] modes : new String[][]{{"INVALID", "UPI"}, {"Pickup", "INVALID"}, {"", "Cash"}, {"Pickup", ""}}) {
            mockMvc.perform(post("/checkout").session(session)
                            .param("studentName", "Private Student").param("phone", "5555555555")
                            .param("orderMode", modes[0]).param("paymentMode", modes[1]))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeHasErrors("checkoutForm"));
        }
        assertThat(orders.findTop8ByEmailIgnoreCaseOrderByCreatedAtDesc("invalid-modes@example.com")).isEmpty();
        assertThat(cartService.count(session)).isEqualTo(1);
    }

    @Test
    void orderServiceRejectsNegativeCartLines() {
        MockHttpSession session = new MockHttpSession();
        cartService.add(1L, session);
        var items = cartService.items(session);
        items.get(0).setQuantity(Integer.MIN_VALUE);
        long before = orders.count();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orderService.placeOrder(new com.studentbites.dto.CheckoutForm(), items))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(orders.count()).isEqualTo(before);
    }

    @Test
    void authenticationRotatesSessionAndUpgradesLegacyPasswords() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String initialId = session.getId();
        mockMvc.perform(post("/signup").session(session).param("fullName", "Secure Student")
                        .param("email", "secure@example.com").param("phone", "5555555555").param("password", "secret123"))
                .andExpect(status().is3xxRedirection());
        assertThat(session.getId()).isNotEqualTo(initialId);
        var user = users.findByEmailIgnoreCase("secure@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2a$12$");
        assertThat(authService.hash("secret123")).isNotEqualTo(authService.hash("secret123"));
        user.setPasswordHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("studentbites:secret123".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        users.save(user);
        assertThat(authService.login("secure@example.com", "wrong")).isEmpty();
        assertThat(users.findByEmailIgnoreCase("secure@example.com").orElseThrow().getPasswordHash()).doesNotStartWith("{bcrypt}");
        MockHttpSession loginSession = new MockHttpSession();
        String beforeLogin = loginSession.getId();
        mockMvc.perform(post("/login").session(loginSession).param("email", "secure@example.com").param("password", "secret123"))
                .andExpect(status().is3xxRedirection());
        assertThat(loginSession.getId()).isNotEqualTo(beforeLogin);
        assertThat(users.findByEmailIgnoreCase("secure@example.com").orElseThrow().getPasswordHash()).startsWith("{bcrypt}");
        assertThat(authService.login("secure@example.com", "secret123")).isPresent();
        assertThat(authService.login("secure@example.com", "wrong")).isEmpty();
        mockMvc.perform(post("/logout").session(loginSession)).andExpect(redirectedUrl("/"));
        assertThat(loginSession.isInvalid()).isTrue();
    }

    @Test
    void cartCanAddItemAndOpenCart() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/cart/add/1").session(session).header("Referer", "http://localhost/menu"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/menu"));

        mockMvc.perform(get("/cart").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void pbkdf2AccountsUpgradeOnlyAfterCorrectLogin() {
        var form = new com.studentbites.dto.SignupForm();
        form.setFullName("Migration Student");
        form.setEmail("pbkdf2@example.com");
        form.setPhone("5555555555");
        form.setPassword("secret123");
        var user = authService.signup(form);
        String legacy = "{pbkdf2}" + org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
                .defaultsForSpringSecurity_v5_8().encode("secret123");
        user.setPasswordHash(legacy);
        users.save(user);
        assertThat(authService.login(user.getEmail(), "wrong")).isEmpty();
        assertThat(users.findByEmailIgnoreCase(user.getEmail()).orElseThrow().getPasswordHash()).isEqualTo(legacy);
        assertThat(authService.login(user.getEmail(), "secret123")).isPresent();
        String upgraded = users.findByEmailIgnoreCase(user.getEmail()).orElseThrow().getPasswordHash();
        assertThat(upgraded).startsWith("{bcrypt}");
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("secret123", upgraded.substring(8))).isTrue();
        assertThat(authService.login(user.getEmail(), "secret123")).isPresent();
    }

    @Test
    void bcryptRejectsPasswordsBeyondItsByteLimit() throws Exception {
        String boundary = "a".repeat(72);
        String encoded = authService.hash(boundary);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(boundary, encoded.substring(8))).isTrue();
        for (String password : new String[]{"a".repeat(73), "\u00e9".repeat(37)}) {
            mockMvc.perform(post("/signup").param("fullName", "Long Password")
                            .param("email", "long@example.com").param("phone", "5555555555")
                            .param("password", password))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Password must be at most 72 UTF-8 bytes")));
        }
        assertThat(users.findByEmailIgnoreCase("long@example.com")).isEmpty();
    }

    @Test
    void homePageRendersWithoutTableLinks() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("StudentBites")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/tables"))));
    }

    @Test
    void guestCannotCheckoutWithoutLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/cart/add/1").session(session).header("Referer", "http://localhost/menu"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/checkout")
                        .session(session)
                        .param("studentName", "Guest Student")
                        .param("email", "guest@example.com")
                        .param("phone", "5555555555")
                        .param("hostelOrClass", "Hostel A")
                        .param("orderMode", "Pickup")
                        .param("paymentMode", "UPI"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(orders.findTop8ByEmailIgnoreCaseOrderByCreatedAtDesc("guest@example.com")).isEmpty();
    }

    @Test
    void signupKeepsEmailUniqueAndLoginWorks() throws Exception {
        MockHttpSession signupSession = new MockHttpSession();

        mockMvc.perform(post("/signup")
                        .session(signupSession)
                        .param("fullName", "Unique Student")
                        .param("email", "Unique.Student@Example.com ")
                        .param("phone", "5555555555")
                        .param("hostelOrClass", "Hostel C")
                .param("password", "secret123"))
        .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(users.findByEmailIgnoreCase("unique.student@example.com")).isPresent();
        assertThat(signupSession.getAttribute(AuthService.USER_EMAIL)).isEqualTo("unique.student@example.com");

        mockMvc.perform(post("/signup")
                        .param("fullName", "Duplicate Student")
                        .param("email", "UNIQUE.STUDENT@example.com")
                        .param("phone", "6666666666")
                        .param("hostelOrClass", "Hostel D")
                        .param("password", "secret123"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("An account already exists for this email")));

        assertThat(users.findAll().stream()
                .filter(user -> user.getEmail().equalsIgnoreCase("unique.student@example.com"))
                .count()).isEqualTo(1);

        MockHttpSession loginSession = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .session(loginSession)
                        .param("email", " UNIQUE.STUDENT@example.com ")
                .param("password", " secret123 "))
        .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(loginSession.getAttribute(AuthService.USER_NAME)).isEqualTo("Unique Student");
        assertThat(loginSession.getAttribute(AuthService.USER_EMAIL)).isEqualTo("unique.student@example.com");
    }

    @Test
    void loggedInStudentOrderAppearsInTheirTracker() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("currentUserName", "Food Student");
        session.setAttribute("currentUserEmail", "food@example.com");
        session.setAttribute("currentUserPhone", "5555555555");
        session.setAttribute("currentUserHostel", "Hostel B");

        mockMvc.perform(post("/cart/add/1").session(session).header("Referer", "http://localhost/menu"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/checkout")
                        .session(session)
                        .param("studentName", "Wrong Name")
                        .param("email", "wrong@example.com")
                        .param("phone", "1111111111")
                        .param("hostelOrClass", "")
                        .param("orderMode", "Pickup")
                        .param("paymentMode", "UPI"))
                .andExpect(status().is3xxRedirection());

        var studentOrders = orders.findTop8ByEmailIgnoreCaseOrderByCreatedAtDesc("food@example.com");
        assertThat(studentOrders).isNotEmpty();
        assertThat(studentOrders.get(0).getStudentName()).isEqualTo("Food Student");

        mockMvc.perform(get("/track").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void orderStatusProgressesAutomaticallyFromCreatedTime() {
        StudentOrder order = new StudentOrder();
        order.setStudentName("Progress Student");
        order.setEmail("progress@example.com");
        order.setPhone("5555555555");
        order.setOrderMode("Pickup");
        order.setPaymentMode("UPI");
        order.setPaymentReference("SIM-TEST");
        order.setTotal(BigDecimal.valueOf(99));
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now().minusMinutes(4));

        StudentOrder saved = orders.save(order);
        StudentOrder refreshed = orderService.refreshStatus(saved);

        assertThat(refreshed.getStatus()).isEqualTo(OrderStatus.READY);

        refreshed.setCreatedAt(LocalDateTime.now().minusMinutes(7));
        refreshed.setStatus(OrderStatus.PREPARING);
        StudentOrder delivered = orderService.refreshStatus(orders.save(refreshed));

        assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void cartSeparatesGuestAndLoggedInStudentData() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/cart/add/1").session(session).header("Referer", "http://localhost/menu"))
                .andExpect(status().is3xxRedirection());
        assertThat(cartService.count(session)).isEqualTo(1);

        cartService.clearGuestCart(session);
        session.setAttribute(AuthService.USER_NAME, "Cart Student");
        session.setAttribute(AuthService.USER_EMAIL, "cart@example.com");
        session.setAttribute(AuthService.USER_PHONE, "5555555555");
        assertThat(cartService.count(session)).isZero();

        mockMvc.perform(post("/cart/add/2").session(session).header("Referer", "http://localhost/menu"))
                .andExpect(status().is3xxRedirection());
        assertThat(cartService.count(session)).isEqualTo(1);

        session.removeAttribute(AuthService.USER_NAME);
        session.removeAttribute(AuthService.USER_EMAIL);
        session.removeAttribute(AuthService.USER_PHONE);
        assertThat(cartService.count(session)).isZero();
    }
}
