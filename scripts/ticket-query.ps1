# Ticket Query Test Scripts (PowerShell)
 = "http://localhost:8080"
 = @{ "Content-Type" = "application/json" }

# Caso 1: encontrado y premiado
 = @{
  ticket = @{
    lotteryCode = "LNB"
    drawNumber  = "2026-10-15"
    ticketNumber= "0012345678"
    series      = "A"
    checkDigit  = "5"
  }
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "/api/v1/agent/ticket-query" -Method Post -Headers ( + @{ "X-Request-Id" = "test-001" }) -Body 

# Caso 2: no encontrado
 = @{
  ticket = @{
    lotteryCode = "LNB"
    drawNumber  = "2026-10-15"
    ticketNumber= "9999999999"
  }
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "/api/v1/agent/ticket-query" -Method Post -Headers ( + @{ "X-Request-Id" = "test-002" }) -Body 

# Caso 3: no premiado
 = @{
  ticket = @{
    lotteryCode = "LNB"
    drawNumber  = "2026-10-15"
    ticketNumber= "0011111111"
  }
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "/api/v1/agent/ticket-query" -Method Post -Headers ( + @{ "X-Request-Id" = "test-003" }) -Body 

# Caso 4: entrada inválida
 = @{
  ticket = @{
    drawNumber  = "invalid-date"
    ticketNumber= "123"
  }
} | ConvertTo-Json -Depth 5
try {
  Invoke-RestMethod -Uri "/api/v1/agent/ticket-query" -Method Post -Headers ( + @{ "X-Request-Id" = "test-004" }) -Body 
} catch {
  .Exception.Response
}
