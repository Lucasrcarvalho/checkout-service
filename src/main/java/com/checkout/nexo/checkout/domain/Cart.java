package com.checkout.nexo.checkout.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Carrinho montado no pre-checkout. Guarda o snapshot de itens e meios permitidos; o checkout
 * cobra o {@code totalAmount} daqui, sem consultar os servicos externos (CHK-04).
 */
@Entity
@Table(name = "cart")
public class Cart {

	@Id
	private UUID id;

	/** Wrapper {@code Long}: {@code null} marca a entidade como nova para o Spring Data. */
	@Version
	private Long version;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private CartStatus status;

	@Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "paid_at")
	private Instant paidAt;

	@ElementCollection
	@CollectionTable(name = "cart_item", joinColumns = @JoinColumn(name = "cart_id"))
	@OrderColumn(name = "line_no")
	private List<CartItem> items = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "cart_payment_method", joinColumns = @JoinColumn(name = "cart_id"))
	private Set<AllowedPaymentMethod> allowedMethods = new LinkedHashSet<>();

	protected Cart() {
	}

	/**
	 * @throws IllegalStateException se os itens tiverem moedas diferentes (inconsistencia do
	 * servico externo, nao do usuario)
	 */
	public static Cart open(UUID id, List<CartItem> items, List<AllowedPaymentMethod> methods, Instant now) {
		String currency = items.getFirst().currency();
		if (items.stream().anyMatch(item -> !item.currency().equals(currency))) {
			throw new IllegalStateException("Itens do carrinho com moedas diferentes");
		}
		Cart cart = new Cart();
		cart.id = id;
		cart.status = CartStatus.OPEN;
		cart.currency = currency;
		cart.totalAmount = items.stream().map(CartItem::unitPrice).reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2);
		cart.createdAt = now;
		cart.items = new ArrayList<>(items);
		cart.allowedMethods = new LinkedHashSet<>(methods);
		return cart;
	}

	/**
	 * Procura o meio pelo nome, sensivel a caixa, depois de normalizar o nome recebido para NFC.
	 */
	public Optional<AllowedPaymentMethod> findMethod(String name) {
		String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
		return allowedMethods.stream().filter(method -> method.name().equals(normalized)).findFirst();
	}

	/**
	 * @throws IllegalStateException se o carrinho ja estiver aprovado
	 */
	public void approve(Instant paidAt) {
		if (isApproved()) {
			throw new IllegalStateException("Carrinho ja aprovado");
		}
		this.status = CartStatus.APPROVED;
		this.paidAt = paidAt;
	}

	public boolean isApproved() {
		return status == CartStatus.APPROVED;
	}

	public UUID getId() {
		return id;
	}

	public Long getVersion() {
		return version;
	}

	public CartStatus getStatus() {
		return status;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

	public List<CartItem> getItems() {
		return Collections.unmodifiableList(items);
	}

	public Set<AllowedPaymentMethod> getAllowedMethods() {
		return Collections.unmodifiableSet(allowedMethods);
	}

}
