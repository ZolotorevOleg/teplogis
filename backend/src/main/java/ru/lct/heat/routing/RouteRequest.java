package ru.lct.heat.routing;

public class RouteRequest {
    public Long targetOrdinal;
    public Integer diameter;
    /** Внутреннее: шаг выборки кандидатов присоединения вдоль существующих линий; null = грубый шаг по умолчанию. */
    @com.fasterxml.jackson.annotation.JsonIgnore public Double tieSpacingM;
    /** Внутреннее: предлагать свободные существующие камеры как кандидатов присоединения вместо ближайших точек сети (разъяснение 11). */
    @com.fasterxml.jackson.annotation.JsonIgnore public boolean preferChambers;
    /** Внутренняя повторная попытка после тайм-аута/предела сложности: только граф преследования, меньше кандидатов присоединения. */
    @com.fasterxml.jackson.annotation.JsonIgnore public boolean fastMode;
    /** Внутреннее: если обычные графы ничего не находят и доказательства замкнутости нет, эскалация до широкого поиска. */
    @com.fasterxml.jackson.annotation.JsonIgnore public boolean wideSearch;
    /** Внутреннее: эта цель всё ещё не решена после широкого поиска; ещё одна эскалация с большим бюджетом и охватом,
     *  зарезервированная для немногих целей, которым она нужна (см. NetworkService.MAX_SUPER_WIDE_TARGETS). */
    @com.fasterxml.jackson.annotation.JsonIgnore public boolean superWide;
    /** Внутреннее: существующие камеры, которые уже заполнены и не должны предлагаться как кандидаты присоединения. */
    @com.fasterxml.jackson.annotation.JsonIgnore public java.util.Set<Long> excludedChambers=java.util.Set.of();
}
