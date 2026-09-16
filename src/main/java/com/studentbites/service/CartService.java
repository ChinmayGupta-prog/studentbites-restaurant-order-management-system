package com.studentbites.service;

import com.studentbites.dto.CartItem;
import com.studentbites.model.FoodItem;
import com.studentbites.repository.FoodItemRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CartService {
    public static final int MAX_QUANTITY = 99;
    private static final String GUEST_CART_KEY = "cart:guest";
    private static final String USER_CART_PREFIX = "cart:user:";
    private final FoodItemRepository foodItems;

    public CartService(FoodItemRepository foodItems) {
        this.foodItems = foodItems;
    }

    public void add(Long itemId, HttpSession session) {
        if (!foodItems.existsById(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found");
        }
        Map<Long, Integer> cart = getCart(session);
        int current = cart.getOrDefault(itemId, 0);
        if (current < 0 || current >= MAX_QUANTITY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum quantity is " + MAX_QUANTITY);
        }
        cart.put(itemId, current + 1);
    }

    public void update(Long itemId, int quantity, HttpSession session) {
        if (quantity < 0 || quantity > MAX_QUANTITY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be between 0 and " + MAX_QUANTITY);
        }
        if (!foodItems.existsById(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found");
        }
        Map<Long, Integer> cart = getCart(session);
        if (quantity <= 0) {
            cart.remove(itemId);
            return;
        }
        cart.put(itemId, quantity);
    }

    public void clear(HttpSession session) {
        getCart(session).clear();
    }

    public void clearGuestCart(HttpSession session) {
        session.removeAttribute(GUEST_CART_KEY);
        session.removeAttribute("cart");
    }

    public List<CartItem> items(HttpSession session) {
        return getCart(session).entrySet().stream()
                .map(entry -> foodItems.findById(entry.getKey())
                        .map(foodItem -> new CartItem(foodItem, entry.getValue()))
                        .orElse(null))
                .filter(item -> item != null)
                .toList();
    }

    public BigDecimal total(HttpSession session) {
        return items(session).stream()
                .map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int count(HttpSession session) {
        return getCart(session).values().stream().mapToInt(Integer::intValue).sum();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Integer> getCart(HttpSession session) {
        String cartKey = cartKey(session);
        Object value = session.getAttribute(cartKey);
        if (value instanceof Map<?, ?>) {
            return (Map<Long, Integer>) value;
        }
        Map<Long, Integer> cart = new LinkedHashMap<>();
        session.setAttribute(cartKey, cart);
        return cart;
    }

    private String cartKey(HttpSession session) {
        Object email = session.getAttribute(AuthService.USER_EMAIL);
        if (email == null || email.toString().isBlank()) {
            return GUEST_CART_KEY;
        }
        return USER_CART_PREFIX + email.toString().trim().toLowerCase();
    }
}
