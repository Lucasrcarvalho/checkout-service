package com.checkout.nexo.checkout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Tentativa de cobranca de um carrinho. Por DATA-01 nao tem campo para numero completo, CVV,
 * titular nem validade: so os 4 ultimos digitos (cartao) ou o codigo Pix.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

	private static final Pattern LAST_DIGITS = Pattern.compile("[0-9]{4}");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "cart_id", nullable = false)
	private Cart cart;

	@Column(name = "method_code", nullable = false, length = 32)
	private String methodCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private AttemptStatus status;

	@Column(name = "amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "last_digits", length = 4)
	private String lastDigits;

	@Column(name = "pix_code", length = 64)
	private String pixCode;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PaymentAttempt() {
	}

	private PaymentAttempt(Cart cart, String methodCode, AttemptStatus status, String lastDigits, String pixCode,
			Instant createdAt) {
		this.cart = cart;
		this.methodCode = methodCode;
		this.status = status;
		this.amount = cart.getTotalAmount();
		this.currency = cart.getCurrency();
		this.lastDigits = lastDigits;
		this.pixCode = pixCode;
		this.createdAt = createdAt;
	}

	public static PaymentAttempt approvedCard(Cart cart, String methodCode, String lastDigits, Instant createdAt) {
		return new PaymentAttempt(cart, methodCode, AttemptStatus.APPROVED, requireFourDigits(lastDigits), null,
				createdAt);
	}

	public static PaymentAttempt approvedPix(Cart cart, String methodCode, String pixCode, Instant createdAt) {
		return new PaymentAttempt(cart, methodCode, AttemptStatus.APPROVED, null, pixCode, createdAt);
	}

	public static PaymentAttempt declined(Cart cart, String methodCode, String lastDigits, Instant createdAt) {
		return new PaymentAttempt(cart, methodCode, AttemptStatus.DECLINED, requireFourDigits(lastDigits), null,
				createdAt);
	}

	private static String requireFourDigits(String lastDigits) {
		if (lastDigits == null || !LAST_DIGITS.matcher(lastDigits).matches()) {
			throw new IllegalArgumentException("lastDigits deve ter exatamente 4 digitos");
		}
		return lastDigits;
	}

	public Long getId() {
		return id;
	}

	public Cart getCart() {
		return cart;
	}

	public String getMethodCode() {
		return methodCode;
	}

	public AttemptStatus getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getLastDigits() {
		return lastDigits;
	}

	public String getPixCode() {
		return pixCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
