/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.individuallending.internal.service;

import io.mifos.individuallending.api.v1.domain.workflow.Action;
import io.mifos.portfolio.api.v1.domain.ChargeDefinition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Myrle Krantz
 */
@SuppressWarnings("WeakerAccess")
@Service
public class PeriodChargeCalculator {
  public PeriodChargeCalculator()
  {
  }

  static Map<Period, BigDecimal> getPeriodAccrualInterestRate(final List<ScheduledCharge> scheduledCharges,
                                                              final int precision) {
    return scheduledCharges.stream()
            .filter(PeriodChargeCalculator::accruedInterestCharge)
            .collect(Collectors.groupingBy(scheduledCharge -> scheduledCharge.getScheduledAction().repaymentPeriod,
                    Collectors.mapping(x -> chargeAmountPerPeriod(x, precision), RateCollectors.compound(precision))));
  }

  private static boolean accruedInterestCharge(final ScheduledCharge scheduledCharge)
  {
    return scheduledCharge.getChargeDefinition().getAccrualAccountDesignator() != null &&
        scheduledCharge.getChargeDefinition().getAccrueAction() != null &&
        scheduledCharge.getChargeDefinition().getAccrueAction().equals(Action.APPLY_INTEREST.name()) &&
        scheduledCharge.getScheduledAction().action == Action.ACCEPT_PAYMENT &&
        scheduledCharge.getScheduledAction().actionPeriod != null;
  }

  static BigDecimal chargeAmountPerPeriod(final ScheduledCharge scheduledCharge, final int precision)
  {
    final ChargeDefinition chargeDefinition = scheduledCharge.getChargeDefinition();
    final ScheduledAction scheduledAction = scheduledCharge.getScheduledAction();
    if (chargeDefinition.getForCycleSizeUnit() == null)
      return chargeDefinition.getAmount();

    final BigDecimal actionPeriodDuration
        = BigDecimal.valueOf(
        scheduledAction.actionPeriod
            .getDuration()
            .getSeconds());
    final Optional<BigDecimal> accrualPeriodDuration = Optional.ofNullable(chargeDefinition.getAccrueAction())
        .flatMap(action -> ScheduledActionHelpers.getAccrualPeriodDurationForAction(Action.valueOf(action)))
        .map(Duration::getSeconds)
        .map(BigDecimal::valueOf);

    final BigDecimal chargeDefinitionCycleSizeUnitDuration
            = BigDecimal.valueOf(
            Optional.ofNullable(chargeDefinition.getForCycleSizeUnit())
                    .orElse(ChronoUnit.YEARS)
                    .getDuration()
                    .getSeconds());

    final BigDecimal accrualPeriodsInCycle = chargeDefinitionCycleSizeUnitDuration.divide(
        accrualPeriodDuration.orElse(actionPeriodDuration), precision, BigDecimal.ROUND_HALF_EVEN);
    final int accrualPeriodsInActionPeriod = actionPeriodDuration.divide(
        accrualPeriodDuration.orElse(actionPeriodDuration), precision, BigDecimal.ROUND_HALF_EVEN)
        .intValueExact();
    final BigDecimal rateForAccrualPeriod = chargeDefinition.getAmount().divide(
        accrualPeriodsInCycle, precision, BigDecimal.ROUND_HALF_EVEN);
    return createCompoundedRate(rateForAccrualPeriod, accrualPeriodsInActionPeriod, precision);
  }

  static BigDecimal createCompoundedRate(final BigDecimal interestRate, final int periodCount, final int precision)
  {
    return Stream.generate(() -> interestRate).limit(periodCount).collect(RateCollectors.compound(precision));
  }
}
