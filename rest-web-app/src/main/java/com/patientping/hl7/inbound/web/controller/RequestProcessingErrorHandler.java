package com.patientping.hl7.inbound.web.controller;

import com.patientping.api.web.dto.Error;
import com.patientping.ejb.exceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class RequestProcessingErrorHandler {

    // TODO: clean up as appropriate... [CAV]

    @ExceptionHandler(Exception.class)
    @ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public String handleAnySystemException(Exception e) {
        return "Critical error processing HL7 message: " + e.getMessage();
    }

    @ExceptionHandler(BadPasswordException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleBadPasswordException(BadPasswordException e) {
        return Error.build(Error.Code.BAD_PASSWORD, e.getMessage());
    }

    @ExceptionHandler(RegistrationException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleRegistrationException(RegistrationException e) {
        return Error.build(Error.Code.REGISTRATION_EXCEPTION);
    }

    @ExceptionHandler(GroupNotFoundException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleGroupNotFoundException(GroupNotFoundException e) {
        return Error.build(Error.Code.GROUP_NOT_FOUND);
    }

    @ExceptionHandler(UserPermissionException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleUserPermissionException(UserPermissionException e) {
        return Error.build(Error.Code.USER_PERMISSION_ERROR);
    }

    @ExceptionHandler(QDCFormRetrievalException.class)
    @ResponseStatus(code = HttpStatus.NOT_FOUND)
    @ResponseBody
    public Error handleQDCFormRetrievalException(QDCFormRetrievalException e) {
        return Error.build(Error.Code.QDC_FORM_NOT_FOUND);
    }

    @ExceptionHandler(QDCPermissionException.class)
    @ResponseStatus(code = HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public Error handleQDCPermissionException(QDCPermissionException e) {
        return Error.build(Error.Code.QDC_PERMISSION_ERROR);
    }

    @ExceptionHandler(QDCFormSubmissionException.class)
    @ResponseStatus(code = HttpStatus.NOT_FOUND)
    @ResponseBody
    public Error handleQDCFormSubmissionException(QDCFormSubmissionException e) {
        return Error.build(Error.Code.QDC_FORM_NOT_FOUND);
    }


    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleAuthenticationException(AuthenticationException e) {
        switch (e.getReason()) {
            case USERNAME_NOT_FOUND:
                return Error.build(Error.Code.USERNAME_NOT_FOUND);
            case DISABLED:
                return Error.build(Error.Code.DISABLED);
            case ACCOUNT_EXPIRED:
                return Error.build(Error.Code.ACCOUNT_EXPIRED);
            case LOCKED:
                return Error.build(Error.Code.ACCOUNT_LOCKED);
            case BAD_CREDENTIALS:
                return Error.build(Error.Code.BAD_CREDENTIALS);
            case NO_ALLOWED_GROUPS:
                return Error.build(Error.Code.NO_ALLOWED_GROUPS);
            case GROUP_UNAUTHORIZED:
                return Error.build(Error.Code.GROUP_UNAUTHORIZED);
            case NO_CONFIGURED_GROUPS:
                return Error.build(Error.Code.NO_CONFIGURED_GROUPS);
            case PASSWORD_EXPIRED:
                return Error.build(Error.Code.PASSWORD_EXPIRED);
            case SNF_USER_SHOULD_LOG_INTO_JANUS:
                return Error.build(Error.Code.SNF_USER_SHOULD_LOG_INTO_JANUS);
            default:
                return Error.build(Error.Code.BAD_CREDENTIALS);
        }
    }

    @ExceptionHandler(InvalidPasswordException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleInvalidPasswordException(InvalidPasswordException e) {
        switch (e.getReason()) {
            case MATCHES_USERNAME:
                return Error.build(Error.Code.PASSWORD_MATCHES_USERNAME);
            case MINIMUM_CHARACTER_LENGTH:
                return Error.build(Error.Code.PASSWORD_MINIMUM_CHARACTER_LENGTH);
            case MINIMUM_UPPERCASE_LETTERS:
                return Error.build(Error.Code.PASSWORD_MINIMUM_UPPERCASE_LETTERS);
            case MINIMUM_NUMBERS:
                return Error.build(Error.Code.PASSWORD_MINIMUM_NUMBERS);
            case PASSWORD_HISTORY:
                return Error.build(Error.Code.PASSWORD_HISTORY);
            default:
                return Error.build(Error.Code.INVALID_PASSWORD);
        }
    }

    @ExceptionHandler(EmailNotSentException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleEmailNotSentException(EmailNotSentException e) {
        return Error.build(Error.Code.EMAIL_NOT_SENT);
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleUserNotFoundException(UserNotFoundException e) {
        return Error.build(Error.Code.USER_NOT_FOUND);
    }

    @ExceptionHandler(UserNotActivatedException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleUserNotActivatedException(UserNotActivatedException e) {
        return Error.build(Error.Code.USER_NOT_ACTIVATED);
    }

    @ExceptionHandler(UserDisabledException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleUserDisabledException(UserDisabledException e) {
        return Error.build(Error.Code.USER_DISABLED);
    }

    @ExceptionHandler(InvalidSecurityTokenException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleInvalidSecurityTokenException(InvalidSecurityTokenException e) {
        return Error.build(Error.Code.INVALID_SECURITY_TOKEN);
    }

    @ExceptionHandler(ExpiredSecurityTokenException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleExpiredSecurityTokenException(ExpiredSecurityTokenException e) {
        return Error.build(Error.Code.EXPIRED_SECURITY_TOKEN);
    }

    @ExceptionHandler(SecurityAnswerValidationException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleSecurityAnswerValidationException(SecurityAnswerValidationException e) {
        return Error.build(Error.Code.INVALID_SECURITY_ANSWER);
    }

    @ExceptionHandler(TentativeMergePatientException.class)
    @ResponseStatus(code = HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Error handleUpdatePatientToExistingPatientException(TentativeMergePatientException e) {
        return Error.build(Error.Code.TENTATIVE_MERGE_PATIENT);
    }

    @ExceptionHandler(UserValidationException.class)
    @ResponseStatus(code = HttpStatus.PRECONDITION_REQUIRED)
    @ResponseBody
    public Error handleControllerAdvice(UserValidationException e) {
        return Error.build(Error.Code.USER_VALIDATION_FAILED, e.getMessage());
    }
}
