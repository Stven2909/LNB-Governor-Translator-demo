# MOCK: WS de tickets simulado para pruebas locales.
# Carlos aun NO ha entregado el WS real. Este script solo imita el contrato
# propuesto (POST /api/v1/tickets/lookup) y NO debe usarse en produccion.
# No usa SQL/JDBC ni datos reales: las respuestas son sinteticas.
param(
    [int]$Port = 9090,
    [string]$Path = "/api/v1/tickets/lookup"
)

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-Host "MOCK WS escuchando en http://127.0.0.1:$Port$Path"
Write-Host "MOCK: Carlos aun no ha entregado el WS real. Ctrl+C para detener."
Write-Host ""

$found = @'
{"found":true,"awarded":true,"ticketNumber":"0012345678","prize":{"amount":"150.00","currency":"USD","category":"CASH"},"collectionStatus":"PENDING","provenance":"SYNTHETIC"}
'@

$notAwarded = @'
{"found":true,"awarded":false,"ticketNumber":"0011111111","prize":null,"collectionStatus":null,"provenance":"SYNTHETIC"}
'@

$notFound = @'
{"found":false,"awarded":false,"ticketNumber":"9999999999","prize":null,"collectionStatus":null,"provenance":"SYNTHETIC"}
'@

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)

            $requestLine = $reader.ReadLine()
            $headers = @{}
            while ($null -ne ($line = $reader.ReadLine()) -and $line -ne "") {
                $idx = $line.IndexOf(':')
                if ($idx -gt 0) {
                    $headers[$line.Substring(0, $idx).Trim().ToLowerInvariant()] = $line.Substring($idx + 1).Trim()
                }
            }

            $length = 0
            if ($headers.ContainsKey("content-length")) {
                $length = [int]$headers["content-length"]
            }

            $body = ""
            if ($headers.ContainsKey("transfer-encoding") -and $headers["transfer-encoding"] -match "chunked") {
                # MOCK: soporta chunked porque el cliente puede no enviar Content-Length.
                $builder = New-Object System.Text.StringBuilder
                while ($true) {
                    $sizeLine = $reader.ReadLine()
                    if ($null -eq $sizeLine) { break }
                    $size = [Convert]::ToInt32($sizeLine.Trim(), 16)
                    if ($size -eq 0) {
                        while ($null -ne ($trailer = $reader.ReadLine()) -and $trailer -ne "") { }
                        break
                    }
                    $chars = New-Object char[] $size
                    $read = 0
                    while ($read -lt $size) {
                        $n = $reader.Read($chars, $read, $size - $read)
                        if ($n -le 0) { break }
                        $read += $n
                    }
                    [void]$builder.Append($chars, 0, $read)
                    [void]$reader.ReadLine()
                }
                $body = $builder.ToString()
            }
            elseif ($length -gt 0) {
                $chars = New-Object char[] $length
                $read = 0
                while ($read -lt $length) {
                    $n = $reader.Read($chars, $read, $length - $read)
                    if ($n -le 0) { break }
                    $read += $n
                }
                $body = -join $chars[0..([Math]::Max($read - 1, 0))]
            }

            $ticket = ""
            if ($body -match '"ticketNumber"\s*:\s*"([^"]+)"') {
                $ticket = $Matches[1]
            }
            $requestId = $headers["x-request-id"]

            if ($ticket -eq "TIMEOUT") {
                # MOCK: simula un WS lento para validar el timeout del agente.
                Start-Sleep -Seconds 10
            }

            $json = switch ($ticket) {
                "0012345678" { $found }
                "0011111111" { $notAwarded }
                "9999999999" { $notFound }
                default { $notFound }
            }

            Write-Host ("MOCK request: {0} | ticket={1} | X-Request-Id={2}" -f $requestLine, $ticket, $requestId)

            $payload = [System.Text.Encoding]::UTF8.GetBytes($json)
            $header = "HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($payload.Length)`r`nConnection: close`r`n`r`n"
            $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
            $stream.Write($headerBytes, 0, $headerBytes.Length)
            $stream.Write($payload, 0, $payload.Length)
            $stream.Flush()
        }
        catch {
            # MOCK: el cliente puede cerrar la conexion al dispararse el timeout.
        }
        finally {
            $client.Close()
        }
    }
}
finally {
    $listener.Stop()
    Write-Host "MOCK WS detenido"
}
