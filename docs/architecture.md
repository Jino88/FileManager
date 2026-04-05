# Architecture Notes

1. Android receives `ACTION_SEND` or `ACTION_SEND_MULTIPLE`
2. App queries upload status for each `fileId`
3. Missing chunks are uploaded sequentially
4. Server merges chunks after `/api/upload/complete`

Future work includes hash validation, discovery, auth, and encrypted transport.
