package io.simakov.analytics.insights;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable thresholds for algorithmic insight rules.
 * Bound from {@code app.insights.thresholds.*} in application.yml.
 */
@ConfigurationProperties(prefix = "app.insights.thresholds")
public class InsightProperties {

    /**
     * Hours an open MR must sit before it is flagged as stuck (STUCK_MRS).
     */
    private int stuckMrHours = 24;

    /**
     * Team median TTM above this value (hours) triggers HIGH_MERGE_TIME.
     */
    private double maxMedianTtmHours = 24.0;

    /**
     * Ratio of current-period median TTM to previous-period median TTM above which
     * MERGE_TIME_SPIKE fires (e.g. 2.0 means "grew more than 2×").
     */
    private double mergeTimeSpikeRatio = 2.0;

    /**
     * Gini coefficient of review load above which REVIEW_LOAD_IMBALANCE fires.
     */
    private double reviewGini = 0.45;

    /**
     * Average MR size in lines above which a user is flagged by LARGE_MR_HABIT.
     */
    private int largeMrLines = 500;

    /**
     * Fraction of current/previous merged MR count below which DELIVERY_DROP fires
     * (e.g. 0.7 means "fell to less than 70% of the previous period").
     */
    private double deliveryDropRatio = 0.70;

    /**
     * Average comments-per-reviewed-MR below which LOW_REVIEW_DEPTH fires.
     * Team average is used (users with zero reviews are excluded).
     */
    private double minCommentsPerMr = 1.5;

    /**
     * Rework ratio above which HIGH_REWORK_RATIO fires.
     */
    private double maxReworkRatio = 0.35;

    /**
     * Minimum merged MRs in the current period for a user to be checked by NO_CODE_REVIEW.
     * Prevents false positives for low-activity users.
     */
    private int minMrsForNoReviewCheck = 5;

    public int getStuckMrHours() {
        return stuckMrHours;
    }

    public void setStuckMrHours(int stuckMrHours) {
        this.stuckMrHours = stuckMrHours;
    }

    public double getMaxMedianTtmHours() {
        return maxMedianTtmHours;
    }

    public void setMaxMedianTtmHours(double maxMedianTtmHours) {
        this.maxMedianTtmHours = maxMedianTtmHours;
    }

    public double getMergeTimeSpikeRatio() {
        return mergeTimeSpikeRatio;
    }

    public void setMergeTimeSpikeRatio(double mergeTimeSpikeRatio) {
        this.mergeTimeSpikeRatio = mergeTimeSpikeRatio;
    }

    public double getReviewGini() {
        return reviewGini;
    }

    public void setReviewGini(double reviewGini) {
        this.reviewGini = reviewGini;
    }

    public int getLargeMrLines() {
        return largeMrLines;
    }

    public void setLargeMrLines(int largeMrLines) {
        this.largeMrLines = largeMrLines;
    }

    public double getDeliveryDropRatio() {
        return deliveryDropRatio;
    }

    public void setDeliveryDropRatio(double deliveryDropRatio) {
        this.deliveryDropRatio = deliveryDropRatio;
    }

    public double getMinCommentsPerMr() {
        return minCommentsPerMr;
    }

    public void setMinCommentsPerMr(double minCommentsPerMr) {
        this.minCommentsPerMr = minCommentsPerMr;
    }

    public double getMaxReworkRatio() {
        return maxReworkRatio;
    }

    public void setMaxReworkRatio(double maxReworkRatio) {
        this.maxReworkRatio = maxReworkRatio;
    }

    public int getMinMrsForNoReviewCheck() {
        return minMrsForNoReviewCheck;
    }

    public void setMinMrsForNoReviewCheck(int minMrsForNoReviewCheck) {
        this.minMrsForNoReviewCheck = minMrsForNoReviewCheck;
    }
}
