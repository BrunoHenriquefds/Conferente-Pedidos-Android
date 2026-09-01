V31.3.1 - Correção da importação XLSX no Android.
Corrigido erro: http://apache.org/xml/features/disallow-doctype-decl
O parser XML agora usa recursos de segurança em modo compatível/best-effort,
evitando falha em aparelhos Android cujo parser não implementa esse recurso.
