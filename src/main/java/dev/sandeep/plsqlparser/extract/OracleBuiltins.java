package dev.sandeep.plsqlparser.extract;

import java.util.Set;
import java.util.regex.Pattern;

/** What counts as an Oracle-supplied package / routine rather than application code. */
public final class OracleBuiltins {
    private OracleBuiltins() {}

    private static final Pattern PACKAGES = Pattern.compile(
            "(?i)^(dbms_\\w+|utl_\\w+|apex_\\w+|owa\\w*|ora_\\w+|sdo_\\w+|ctx_\\w+|sys|standard|htp|htf|wpg_docload|xmldom|xmlparser|"
                    + "xslprocessor|anydata|anytype|anydataset|diutil|plitblm|ku\\$_\\w+|sa_\\w+|json_\\w+)$");

    private static final Set<String> FUNCTIONS = Set.of(
            "raise_application_error", "sys_context", "userenv", "sqlerrm", "sqlcode", "sys_guid", "sys_extract_utc", "ora_hash",
            "nvl", "nvl2", "decode", "coalesce", "nullif", "to_char", "to_date", "to_number", "to_timestamp", "to_clob", "to_blob",
            "trunc", "round", "substr", "instr", "length", "lengthb", "upper", "lower", "trim", "ltrim", "rtrim", "replace", "lpad",
            "rpad", "concat", "initcap", "translate", "chr", "ascii", "add_months", "months_between", "last_day", "next_day",
            "greatest", "least", "abs", "ceil", "floor", "mod", "power", "sqrt", "sign", "cast", "regexp_like", "regexp_substr",
            "regexp_replace", "regexp_instr", "regexp_count", "listagg", "count", "sum", "avg", "min", "max", "row_number", "rank",
            "dense_rank", "lead", "lag", "systimestamp", "sysdate", "current_timestamp", "numtodsinterval", "numtoyminterval",
            "empty_clob", "empty_blob", "bitand", "hextoraw", "rawtohex", "dump", "vsize", "xmltype", "sys_xmlgen", "anydata",
            "sys_connect_by_path", "to_dsinterval", "to_yminterval", "nls_upper", "nls_lower", "soundex", "reverse", "stddev", "variance",
            "median", "first_value", "last_value", "ntile", "cume_dist", "percent_rank", "treat", "extract", "existsnode", "extractvalue");

    private static final Set<String> COLLECTION_METHODS = Set.of(
            "count", "first", "last", "next", "prior", "exists", "delete", "extend", "trim", "limit");

    /** Coarse side-effect category of an Oracle-supplied package, for the external-I/O summary. */
    public static String category(String pkg) {
        String p = pkg.toUpperCase();
        if (p.equals("UTL_FILE")) return "FILE_IO";
        if (p.matches("UTL_HTTP|UTL_TCP|UTL_SMTP|UTL_MAIL|UTL_INADDR|UTL_URL|UTL_DBWS|APEX_WEB_SERVICE|APEX_MAIL")) return "NETWORK";
        if (p.matches("DBMS_SCHEDULER|DBMS_JOB")) return "SCHEDULER";
        if (p.matches("DBMS_PIPE|DBMS_ALERT|DBMS_AQ|DBMS_AQADM")) return "MESSAGING";
        if (p.equals("DBMS_LOCK")) return "LOCKING";
        if (p.matches("DBMS_OUTPUT|DBMS_DEBUG")) return "CONSOLE";
        if (p.matches("DBMS_SESSION|DBMS_APPLICATION_INFO|DBMS_APPLICATION_INFO")) return "SESSION";
        if (p.matches("HTP|HTF|WPG_DOCLOAD|OWA\\w*|APEX_\\w+")) return "WEB";
        if (p.equals("DBMS_SQL")) return "DYNAMIC_SQL";
        if (p.matches("DBMS_CRYPTO|DBMS_OBFUSCATION_TOOLKIT|DBMS_RANDOM")) return "CRYPTO_RANDOM";
        if (p.matches("DBMS_LOB|UTL_RAW")) return "LOB";
        if (p.matches("DBMS_XMLGEN|DBMS_XMLDOM|XMLDOM|XMLPARSER|XSLPROCESSOR|DBMS_XMLSTORE")) return "XML";
        if (p.matches("DBMS_STATS|DBMS_UTILITY|DBMS_METADATA|DBMS_REDEFINITION")) return "ADMIN";
        return "OTHER_BUILTIN";
    }

    public static boolean isPackage(String name) {
        return PACKAGES.matcher(name).matches();
    }

    public static boolean isFunction(String name) {
        return FUNCTIONS.contains(name.toLowerCase());
    }

    public static boolean isCollectionMethod(String name) {
        return COLLECTION_METHODS.contains(name.toLowerCase());
    }
}
