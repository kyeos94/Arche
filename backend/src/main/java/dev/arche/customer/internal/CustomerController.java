package dev.arche.customer.internal;

import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
class CustomerController {

	record RegisterRequest(@NotBlank String name,
			@NotBlank @Pattern(regexp = "[0-9]{6}-?[0-9]{7}", message = "주민등록번호 형식이 아닙니다") String residentNo,
			@NotNull LocalDate birthDate, String phone, String address) {

		@Override
		public String toString() {
			return "RegisterRequest[name=" + name + "]";
		}
	}

	record RevealRequest(@NotBlank String reason) {
	}

	record RevealResponse(String residentNo) {
	}

	private final Customers customers;

	CustomerController(Customers customers) {
		this.customers = customers;
	}

	@PostMapping
	@PreAuthorize("hasAuthority('loan.create') or hasAuthority('application.review')")
	CustomerView register(@Valid @RequestBody RegisterRequest body) {
		return customers.registerOrFind(new Customers.NewCustomer(body.name(), body.residentNo(), body.birthDate(),
				body.phone(), body.address()));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('loan.read') or hasAuthority('application.review')")
	CustomerView get(@PathVariable long id) {
		return customers.get(id);
	}

	@PostMapping("/{id}/reveal")
	@PreAuthorize("hasAuthority('customer.id.reveal')")
	RevealResponse reveal(@PathVariable long id, @Valid @RequestBody RevealRequest body) {
		return new RevealResponse(customers.revealResidentNo(id, body.reason()));
	}
}
