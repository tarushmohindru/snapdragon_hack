class DomainError(Exception):
    code = "domain_error"
    status_code = 400


class SessionNotFound(DomainError):
    code = "session_not_found"
    status_code = 404


class SessionExpired(DomainError):
    code = "session_expired"
    status_code = 410


class InvalidJoinCode(DomainError):
    code = "invalid_join_code"
    status_code = 403


class SessionFull(DomainError):
    code = "session_full"
    status_code = 409


class Unauthorized(DomainError):
    code = "unauthorized"
    status_code = 401


class CalibrationRequired(DomainError):
    code = "calibration_required"
    status_code = 409


class CalibrationFailed(DomainError):
    code = "calibration_failed"
    status_code = 422


class PayloadTooLarge(DomainError):
    code = "payload_too_large"
    status_code = 413


class MlServiceUnavailable(DomainError):
    code = "ml_service_unavailable"
    status_code = 503


class MlServiceRejected(DomainError):
    code = "ml_service_rejected"
    status_code = 422
