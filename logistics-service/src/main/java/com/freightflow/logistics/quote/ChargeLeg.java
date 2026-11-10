package com.freightflow.logistics.quote;

/** The parts of a door-to-door move that a forwarder can bill, in the order the cargo goes through them. */
public enum ChargeLeg {
    /** Pickup at the shipper, origin terminal handling and export clearance. */
    ORIGIN_HANDLING,
    /** Main carriage (sea or air) and its fuel surcharge. */
    MAIN_CARRIAGE,
    /** Destination terminal handling and delivery to the named place. */
    DESTINATION_HANDLING,
    /** Import customs clearance (the brokerage fee). Duties and taxes are not part of it. */
    IMPORT_CLEARANCE
}
