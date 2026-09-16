package com.studentbites.controller;

import com.studentbites.dto.LoginForm;
import com.studentbites.dto.SignupForm;
import com.studentbites.service.AuthService;
import com.studentbites.service.CartService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AuthController {
    private final AuthService authService;
    private final CartService cartService;

    public AuthController(AuthService authService, CartService cartService) {
        this.authService = authService;
        this.cartService = cartService;
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (!model.containsAttribute("loginForm")) {
            model.addAttribute("loginForm", new LoginForm());
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@Valid @ModelAttribute LoginForm loginForm,
                        BindingResult result,
                        HttpServletRequest request,
                        HttpSession session,
                        RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return "login";
        }
        return authService.login(loginForm.getEmail(), loginForm.getPassword())
                .map(user -> {
                    request.changeSessionId();
                    cartService.clearGuestCart(session);
                    authService.remember(user, session);
                    redirectAttributes.addFlashAttribute("toast", "Welcome, " + user.getFullName());
                    return "redirect:/";
                })
                .orElseGet(() -> {
                    result.reject("login.invalid", "Invalid email or password");
                    return "login";
                });
    }

    @GetMapping("/signup")
    public String signup(Model model) {
        if (!model.containsAttribute("signupForm")) {
            model.addAttribute("signupForm", new SignupForm());
        }
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute SignupForm signupForm,
                         BindingResult result,
                         HttpServletRequest request,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        if (authService.emailExists(signupForm.getEmail())) {
            result.rejectValue("email", "email.exists", "An account already exists for this email");
        }
        if (result.hasErrors()) {
            return "signup";
        }
        var user = signupSafely(signupForm, result);
        if (result.hasErrors()) {
            return "signup";
        }
        request.changeSessionId();
        cartService.clearGuestCart(session);
        authService.remember(user, session);
        redirectAttributes.addFlashAttribute("toast", "Account created. You can now order and track your food.");
        return "redirect:/";
    }

    private com.studentbites.model.AppUser signupSafely(SignupForm signupForm, BindingResult result) {
        try {
            return authService.signup(signupForm);
        } catch (AuthService.DuplicateEmailException | DataIntegrityViolationException exception) {
            result.rejectValue("email", "email.exists", "An account already exists for this email");
            return null;
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session, RedirectAttributes redirectAttributes) {
        authService.logout(session);
        redirectAttributes.addFlashAttribute("toast", "Logged out successfully");
        return "redirect:/";
    }
}
