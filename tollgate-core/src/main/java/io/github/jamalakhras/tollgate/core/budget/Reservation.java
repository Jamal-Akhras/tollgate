package io.github.jamalakhras.tollgate.core.budget;

import io.github.jamalakhras.tollgate.core.money.Money;

public record Reservation(String tenant, Budget budget, Money amount) {}
