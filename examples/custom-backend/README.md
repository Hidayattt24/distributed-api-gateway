# Use the gateway with your own service

This example needs JDK 21 and Python 3. It runs without Docker, Redis or the PetClinic services.

From the repository root, open two PowerShell terminals:

```powershell
# Terminal 1: your backend (replace this with your own HTTP service later)
python examples/custom-backend/backend.py
```

```powershell
# Terminal 2: gateway
$env:GATEWAY_CONFIG = 'examples/custom-backend/gateway.properties'
.\gradlew.bat :gateway:gateway-runtime:run
```

Then run `curl.exe -i http://localhost:8080/demo/hello`. The JSON response should include `"path": "/demo/hello"`. The gateway forwards the original path. Replace `route.demo.upstream` with your service's HTTP origin and adapt the prefix and policy. Stop the processes with Ctrl+C.
