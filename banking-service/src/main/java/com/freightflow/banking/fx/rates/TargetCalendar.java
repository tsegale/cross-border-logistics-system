package com.freightflow.banking.fx.rates;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/**
 * The days the ECB publishes euro reference rates: TARGET working days (ADR 0034). TARGET is closed on Saturdays,
 * Sundays, New Year's Day, Good Friday, Easter Monday, 1 May, Christmas Day and 26 December, the long-term calendar
 * the ECB's Governing Council set in December 2000 for 2002 onwards "until further notice". No rates are published on
 * those days, so a weekend or a holiday never makes a rate older.
 */
public final class TargetCalendar {

    private static final Set<MonthDay> FIXED_CLOSING_DAYS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(5, 1), MonthDay.of(12, 25), MonthDay.of(12, 26));

    private TargetCalendar() {
    }

    public static boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || FIXED_CLOSING_DAYS.contains(MonthDay.from(date))) {
            return false;
        }
        LocalDate easter = easterSunday(date.getYear());
        return !date.equals(easter.minusDays(2)) && !date.equals(easter.plusDays(1));
    }

    /** The working day itself, or the last one before it. */
    public static LocalDate onOrBefore(LocalDate date) {
        LocalDate day = date;
        while (!isWorkingDay(day)) {
            day = day.minusDays(1);
        }
        return day;
    }

    /** Working days after {@code from} up to and including {@code to}; 0 when {@code to} is not after it. */
    public static int workingDaysBetween(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate day = from.plusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (isWorkingDay(day)) {
                days++;
            }
        }
        return days;
    }

    /** Western Easter Sunday in the Gregorian calendar (the anonymous algorithm of Meeus, Jones and Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
