package com.example.AviaryService.services.alerts;

// The bottom of an alert email, per recipient: who added them, for which
// aircraft, their own stop link, and the postal address (CAN-SPAM). The
// link and address may be blank, then they're left out.
public record EmailFooter(String operator, String tails, String unsubscribeUrl, String postalAddress) {

    public boolean hasUnsubscribe() {
        return unsubscribeUrl != null && !unsubscribeUrl.isBlank();
    }

    public boolean hasPostalAddress() {
        return postalAddress != null && !postalAddress.isBlank();
    }
}
