package io.simakov.analytics.domain.model.enums;

public enum PeriodType {

    LAST_7_DAYS(7), LAST_30_DAYS(30), LAST_90_DAYS(90), LAST_180_DAYS(180), LAST_360_DAYS(360), CUSTOM(0);

    private final int days;

    PeriodType(int days) {
        this.days = days;
    }

    public int toDays() {
        return days;
    }
}
