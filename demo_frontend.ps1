
cd frontend 

pnpm install 
pnpm dev          # http://localhost:9100 (proxies /api to :8080)

# curl -N -X POST localhost:8080/api/chat/completions -H 'Content-Type: application/json' \
#      -d '{"question":"How many wholesale customers are there?"}'