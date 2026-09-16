package com.studentbites.controller;

import com.studentbites.model.OrderStatus;
import com.studentbites.service.OrderService;
import com.studentbites.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InvoiceController {
    private final OrderService orderService;

    public InvoiceController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/invoice/{id}")
    public String invoice(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes, HttpSession session) {
        String email = (String) session.getAttribute(AuthService.USER_EMAIL);
        if (email == null) {
            return "redirect:/login";
        }
        return orderService.findByIdWithLiveStatus(id, email)
                .map(order -> {
                    model.addAttribute("order", order);
                    model.addAttribute("statuses", OrderStatus.values());
                    model.addAttribute("autoRefresh", order.getStatus() != OrderStatus.DELIVERED);
                    return "invoice";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("toast", "Invoice not found");
                    return "redirect:/menu";
                });
    }
}
