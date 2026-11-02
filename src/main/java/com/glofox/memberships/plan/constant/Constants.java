package com.glofox.memberships.plan.constant;

/** Central names and protocol values for the plan service. */
public final class Constants {
    private Constants() {}

    public static final String PLAN_TABLE_NAME = "plans";
    public static final String PLANS_PATH = "/v1/plans";

    public static final String PLANS_CACHE = PLAN_TABLE_NAME;
    public static final String PLANS_LIST_CACHE_KEY = "all";

    public static final String PLAN_NOT_FOUND_CODE = "PLAN_NOT_FOUND";
    public static final String PLAN_INACTIVE_CODE = "PLAN_INACTIVE";
}
