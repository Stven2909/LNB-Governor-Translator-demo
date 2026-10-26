# Evidencia — Vertex AI function calling (ticket-agent)

**Fecha:** 2026-10-25
**Cuenta:** steven@avanzatech.xyz
**Proyecto:** proy-comercial-dev-lnb
**Modelo:** gemini-3.8-flash

## 1. Disponibilidad por ubicacion

| Ubicacion | Endpoint | Resultado |
|---|---|---|
| us-central1 | https://us-central1-aiplatform.googleapis.com | HTTP 404 NOT_FOUND |
| global | https://aiplatform.googleapis.com | HTTP 200 OK |

Mensaje de us-central1:

```text
Publisher model `projects/proy-comercial-dev-lnb/locations/us-central1/publishers/google/models/gemini-3.8-flash`
was not found or your project does not have access to it.
```

Conclusion: `gemini-3.8-flash` solo esta disponible en `locations/global` para este proyecto.
Por eso `APP_TICKET_AGENT_REGION` tiene default `global`.

## 2. Inferencia de texto (global)

```bash
TOKEN=$(gcloud auth print-access-token)
curl -i -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://aiplatform.googleapis.com/v1/projects/proy-comercial-dev-lnb/locations/global/publishers/google/models/gemini-3.8-flash:generateContent" \
  -d '{"contents":[{"role":"user","parts":[{"text":"Responde solo: OK"}]}]}'
```

Respuesta: `HTTP 200`, `finishReason=STOP`, `modelVersion=gemini-3.8-flash`.

## 3. Function calling con mode=ANY (global)

```bash
TOKEN=$(gcloud auth print-access-token)
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "https://aiplatform.googleapis.com/v1/projects/proy-comercial-dev-lnb/locations/global/publishers/google/models/gemini-3.8-flash:generateContent" \
  -d '{
    "contents":[{"role":"user","parts":[{"text":"Consulta el boleto LNB, sorteo 2026-10-15, numero 0012345678"}]}],
    "tools":[{"functionDeclarations":[{
      "name":"consultar_boleto",
      "description":"Consulta un boleto",
      "parameters":{"type":"OBJECT","properties":{
        "lotteryCode":{"type":"STRING"},
        "drawNumber":{"type":"STRING"},
        "ticketNumber":{"type":"STRING"}
      },"required":["lotteryCode","drawNumber","ticketNumber"]}
    }]}],
    "toolConfig":{"functionCallingConfig":{"mode":"ANY"}}
  }' | python3 -m json.tool
```

Resultado (extracto):

```json
{
  "candidates": [{
    "content": {
      "role": "model",
      "parts": [{
        "functionCall": {
          "name": "consultar_boleto",
          "args": {
            "lotteryCode": "LNB",
            "ticketNumber": "0012345678",
            "drawNumber": "2026-10-15"
          },
          "id": "call_3638091"
        }
      }]
    },
    "finishReason": "STOP"
  }],
  "modelVersion": "gemini-3.8-flash",
  "usageMetadata": {
    "promptTokenCount": 72,
    "candidatesTokenCount": 53,
    "totalTokenCount": 205
  }
}
```

## 4. Conclusion

- El modelo invoca `consultar_boleto` con los argumentos correctos bajo `mode=ANY`.
- El agente puede validar esos argumentos contra la clave original, tal como hace `TicketAgent.verifyArgs`.
- Queda pendiente ejecutar el worker completo con `APP_TICKET_AGENT_MODEL_CLIENT=VERTEX` usando credenciales de la cuenta corporativa.
- El token de acceso no se guarda en este archivo.
