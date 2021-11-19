package com.alibaba.druid.pool.vendor;

import com.alibaba.druid.pool.ExceptionSorter;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.Properties;

public class ZjrMySQLExceptionSorter implements ExceptionSorter {
    @Override
    public boolean isExceptionFatal(SQLException e) {
        if (e instanceof SQLRecoverableException) {
            return true;
        }
        int errorCode = e.getErrorCode();
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
            return true;
        }
        switch (errorCode) {
            // Communications Errors
            case 1040: // ER_CON_COUNT_ERROR
            case 1042: // ER_BAD_HOST_ERROR
            case 1043: // ER_HANDSHAKE_ERROR
            case 1047: // ER_UNKNOWN_COM_ERROR
            case 1081: // ER_IPSOCK_ERROR
            case 1129: // ER_HOST_IS_BLOCKED
            case 1130: // ER_HOST_NOT_PRIVILEGED
                // Authentication Errors
            case 1045: // ER_ACCESS_DENIED_ERROR
                // Resource errors
            case 1004: // ER_CANT_CREATE_FILE
            case 1005: // ER_CANT_CREATE_TABLE
            case 1015: // ER_CANT_LOCK
            case 1021: // ER_DISK_FULL
            case 1041: // ER_OUT_OF_RESOURCES
                // Out-of-memory errors
            case 1037: // ER_OUTOFMEMORY
            case 1038: // ER_OUT_OF_SORTMEMORY
                // Access denied
            case 1142: // ER_TABLEACCESS_DENIED_ERROR
            case 1227: // ER_SPECIFIC_ACCESS_DENIED_ERROR

            case 1023: // ER_ERROR_ON_CLOSE

            case 1290: // ER_OPTION_PREVENTS_STATEMENT
                return true;
            default:
                break;
        }
        return false;
    }

    @Override
    public void configFromProperties(Properties properties) {

    }
}
