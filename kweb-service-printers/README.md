# kweb-service-printers

The RFC 0046 application-scoped printer-discovery service. It reports exactly the
printers and facts the host's print subsystem publishes, adds no renderer
surface, and never submits a print job.

- Contract: `KWebPrinters` with one host-only `list` operation.
- Provider: the JDK print service (`javax.print`), which dispatches to CUPS on
  macOS and Linux and to the WinSpool print subsystem on Windows.
- Unknown facts stay `null`; an empty printer list is a valid observation; a host
  without a print subsystem fails with `printers.unavailable`.

Verify with `./gradlew :kweb-service-printers:check`, which also runs the
`printers-integration` probe comparing the snapshot with `lpstat` (macOS/Linux)
or `Get-Printer` (Windows). Print submission, dialogs, and job lifecycle remain
RFC 0026 objectives.
