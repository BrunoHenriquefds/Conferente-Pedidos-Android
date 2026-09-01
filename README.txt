CONFERENTE PEDIDOS ONLINE - ANDROID NATIVO V31

Projeto reconstruído sem Kivy.
Interface: WebView responsiva em modo paisagem, baseada no layout aprovado.
Backend: Java Android + SQLite + Storage Access Framework.

IMPLEMENTADO NESTA BASE:
- layout paisagem com menu lateral, bipagem, cards, tabela, capa e detalhes
- listas: criar, selecionar, renomear, excluir
- importação XLSX por seletor nativo Android
- importação de localizações SS
- importação de negativos
- conferência por ISBN/EAN/SKU
- conferência de pacotes/rastreio (normalização dos 11 dígitos)
- desfazer última conferência
- pesquisa, filtros e ordenação
- catálogo permanente de localizações
- capa por ISBN (Open Library) + pesquisa web
- exportação XLSX de faltantes via seletor nativo Android
- banco SQLite local

OBSERVAÇÃO:
A importação PDF Amazon da V31 ainda precisa de um parser PDF Android dedicado. O restante desta base não depende de Kivy/Python.

GITHUB:
Actions > Gerar APK Android Nativo > Run workflow
