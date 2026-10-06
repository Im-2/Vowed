/** An error with an HTTP status and a stable machine-readable code. Messages never contain secrets. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export const badRequest = (code: string, message: string, details?: unknown) => new ApiError(400, code, message, details);
export const unauthorized = (message = "authentication required") => new ApiError(401, "unauthorized", message);
export const forbidden = (code: string, message: string) => new ApiError(403, code, message);
export const notFound = (what: string) => new ApiError(404, "not_found", `${what} not found`);
export const conflict = (code: string, message: string) => new ApiError(409, code, message);
export const tooMany = (retryAfterSec: number) =>
  new ApiError(429, "rate_limited", `too many requests, retry in ${retryAfterSec}s`, { retryAfterSec });
