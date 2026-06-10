# 🛡️ Events2026Shield

Dashboard que **monitora eventos e notícias do setor** com **datas e alertas**, focado em três áreas:

- **Perícia / Forense Digital** (IPED, cadeia de custódia, prova digital)
- **Cibersegurança** (ransomware, vazamentos, CVEs, ameaças)
- **Forense + Jurídico / Direito Digital** (LGPD, ANPD, prova em juízo)

Feito em **Python puro (biblioteca padrão)** — não precisa instalar nada além do Python 3.

![setores](https://img.shields.io/badge/setores-forense%20%C2%B7%20ciberseg%20%C2%B7%20jur%C3%ADdico-blue)

## ✨ O que faz

| Recurso | Descrição |
|---|---|
| 📰 **Notícias** | Agrega feeds RSS/Atom dos 3 setores, ordenados por data, com fonte e resumo. |
| 📅 **Agenda 2026** | Eventos do setor com data, local e contagem regressiva. |
| 🔔 **Alertas por palavra-chave** | Notícias que contêm seus termos (ex.: `ransomware`, `LGPD`, `prova digital`) são destacadas. |
| ⏰ **Alertas de data** | Eventos dentro da janela de antecedência (padrão 45 dias) entram em estado de alerta. |
| 🎛️ **Filtros por setor** | Veja tudo ou só o setor desejado. |
| ⚙️ **Configurável** | Edite palavras-chave e janela direto na interface; feeds e eventos em arquivos JSON. |

## 🚀 Como rodar

```bash
python3 app.py
# ou
./run.sh
# porta customizada:
PORT=9000 python3 app.py
```

Abra **http://127.0.0.1:8000** no navegador.

> Requer apenas **Python 3.8+**. Sem `pip install`.

## 🗂️ Estrutura

```
Events2026Shield/
├── app.py              # servidor HTTP + API (stdlib)
├── monitor/
│   ├── feeds.py        # busca/parse de RSS e Atom (paralelo + cache)
│   ├── events.py       # cálculo de proximidade e estado dos eventos
│   └── alerts.py       # marcação de notícias por palavra-chave
├── data/
│   ├── feeds.json      # fontes RSS por setor  ← personalize aqui
│   ├── events.json     # agenda de eventos 2026 ← personalize aqui
│   └── alerts.json     # palavras-chave + janela de alerta
└── web/                # frontend (HTML/CSS/JS)
```

## ⚙️ Personalização

**Adicionar uma fonte de notícias** — edite `data/feeds.json`:

```json
{ "name": "Meu Blog", "url": "https://exemplo.com/feed/" }
```

**Adicionar um evento** — edite `data/events.json`:

```json
{
  "id": "meu-evento-2026",
  "nome": "Congresso de Perícia 2026",
  "setor": "forense",
  "local": "São Paulo, Brasil",
  "inicio": "2026-10-15",
  "fim": "2026-10-17",
  "url": "https://exemplo.com",
  "status": "confirmado",
  "notas": "Descrição curta."
}
```

**Alertas** — ajuste pela interface ou edite `data/alerts.json`.

## 🔌 API

| Método | Rota | Descrição |
|---|---|---|
| GET | `/api/noticias?setores=forense,juridico&force=1` | Notícias agregadas + alertas |
| GET | `/api/eventos` | Eventos enriquecidos com estado/dias |
| GET | `/api/setores` | Setores e contagem de fontes |
| GET/POST | `/api/alertas` | Lê/atualiza palavras-chave e janela |

## ⚠️ Observações

- As **datas marcadas como "estimado"** seguem o padrão histórico de cada evento — **confirme no site oficial** e atualize `data/events.json`.
- Se a rede bloquear algum feed (CORS não se aplica: o backend é quem busca), a fonte aparece como "falhou" no painel, mas o resto continua funcionando.
- O cache de notícias é de 10 minutos; use o botão **↻ Atualizar** para forçar.
