package n.startapp.services.lexical

/**
 * JSON schema handed to the provider for the annotation call.
 *
 * Mirrors [n.startapp.models.lexical.DraftEntry] exactly. Two properties matter beyond shape:
 *
 *  - `additionalProperties: false` at every level, so there is no field for IPA, audio URLs,
 *    citations, or sense ids — the model cannot invent what it cannot express;
 *  - every property listed in `required`, because OpenAI's strict mode rejects a schema whose
 *    `required` list is not exhaustive. Optionality is expressed as a nullable type instead.
 */
const val LEXICAL_ENTRY_SCHEMA_NAME = "lexical_entry"

val ALLOWED_POS = listOf(
    "noun", "verb", "adjective", "adverb", "pronoun", "preposition", "conjunction",
    "determiner", "numeral", "interjection", "phrase", "idiom", "phrasal verb",
    "prefix", "suffix", "abbreviation"
)

val LEXICAL_ENTRY_JSON_SCHEMA: String = """
{
  "name": "$LEXICAL_ENTRY_SCHEMA_NAME",
  "strict": true,
  "schema": {
    "type": "object",
    "additionalProperties": false,
    "required": ["lemma", "kind", "etymology", "frequencyBand", "usageNotes", "posGroups"],
    "properties": {
      "lemma": { "type": "string" },
      "kind": {
        "type": "string",
        "enum": ["WORD", "PHRASE", "IDIOM", "PHRASAL_VERB", "ABBREVIATION", "PROPER_NOUN"]
      },
      "etymology": { "type": ["string", "null"] },
      "frequencyBand": {
        "type": ["string", "null"],
        "enum": ["очень частотное", "частотное", "редкое", null]
      },
      "usageNotes": { "type": "array", "items": { "type": "string" } },
      "posGroups": {
        "type": "array",
        "items": {
          "type": "object",
          "additionalProperties": false,
          "required": ["pos", "posRu", "forms", "senses"],
          "properties": {
            "pos": { "type": "string", "enum": [${ALLOWED_POS.joinToString(", ") { "\"$it\"" }}] },
            "posRu": { "type": "string" },
            "forms": {
              "type": ["object", "null"],
              "additionalProperties": false,
              "required": ["plural", "past", "pastParticiple", "presentParticiple", "thirdPerson", "comparative", "superlative"],
              "properties": {
                "plural": { "type": ["string", "null"] },
                "past": { "type": ["string", "null"] },
                "pastParticiple": { "type": ["string", "null"] },
                "presentParticiple": { "type": ["string", "null"] },
                "thirdPerson": { "type": ["string", "null"] },
                "comparative": { "type": ["string", "null"] },
                "superlative": { "type": ["string", "null"] }
              }
            },
            "senses": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["definitionEn", "definitionRu", "translationsRu", "register", "cefr", "domain", "examples", "collocations", "synonyms", "antonyms", "sourceRefs", "generated", "usageNote"],
                "properties": {
                  "definitionEn": { "type": "string" },
                  "definitionRu": { "type": "string" },
                  "translationsRu": { "type": "array", "minItems": 1, "maxItems": 4, "items": { "type": "string" } },
                  "register": {
                    "type": "string",
                    "enum": ["neutral", "formal", "informal", "slang", "vulgar", "dated", "literary", "technical"]
                  },
                  "cefr": { "type": ["string", "null"], "enum": ["A1", "A2", "B1", "B2", "C1", "C2", null] },
                  "domain": { "type": ["string", "null"] },
                  "examples": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 2,
                    "items": {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["en", "ru", "sourceRef"],
                      "properties": {
                        "en": { "type": "string" },
                        "ru": { "type": "string" },
                        "sourceRef": { "type": ["integer", "null"] }
                      }
                    }
                  },
                  "collocations": {
                    "type": "array",
                    "maxItems": 5,
                    "items": {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["pattern", "ru"],
                      "properties": {
                        "pattern": { "type": "string" },
                        "ru": { "type": ["string", "null"] }
                      }
                    }
                  },
                  "synonyms": { "type": "array", "maxItems": 6, "items": { "type": "string" } },
                  "antonyms": { "type": "array", "maxItems": 6, "items": { "type": "string" } },
                  "sourceRefs": { "type": "array", "items": { "type": "integer" } },
                  "generated": { "type": "boolean" },
                  "usageNote": { "type": ["string", "null"] }
                }
              }
            }
          }
        }
      }
    }
  }
}
""".trimIndent()
