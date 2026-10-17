package net.resheim.timekeeper.server;

import java.net.IDN;
import java.util.Locale;

/** Deliberately accepts unquoted mailboxes; domain names are stored in ASCII form. */
final class RecoveryEmail {
    private RecoveryEmail() { }

    static String normalize(String input) {
        if(input==null || input.length()>320) throw ApiFailure.validation();
        String value=input.strip();int at=value.indexOf('@');
        if(at<1 || at!=value.lastIndexOf('@') || at>64) throw ApiFailure.validation();
        String local=value.substring(0,at),domain;
        if(!local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+") || local.startsWith(".") || local.endsWith(".") || local.contains("..")) throw ApiFailure.validation();
        try {domain=IDN.toASCII(value.substring(at+1),IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);}
        catch(IllegalArgumentException invalid) {throw ApiFailure.validation();}
        if(domain.length()>253 || !domain.contains(".") || domain.endsWith(".") || (local+"@"+domain).length()>254) throw ApiFailure.validation();
        return local+"@"+domain;
    }

    // Case-insensitive equality prevents accidentally selecting the same recovery mailbox twice.
    static boolean same(String first,String second) {return first!=null && second!=null && first.equalsIgnoreCase(second);}
}
