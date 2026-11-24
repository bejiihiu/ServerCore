import { describe, expect, test } from "bun:test"
import { existsSync, readFileSync } from "node:fs"
import path from "node:path"
import {
  BACKEND_TO_PLUGIN_TYPES,
  MESSAGE_TYPES,
  payloadSchemas,
} from "../src/websocket/protocol/message-types.js"

/**
 * cross-language contract: protocol/messages.json is the single source the
 * backend and the plugin both check themselves against. this suite owns the
 * backend half — if a zod payload schema, a direction set or the manifest
 * drifts from the other three, the build fails here.
 */

const manifestPath = path.join(import.meta.dir, "../../protocol/messages.json")

interface ManifestField {
  name: string
  type: "uuid" | "objectId" | "string" | "integer" | "number" | "boolean" | "enum" | "object"
  required: boolean
  values?: string[]
  fields?: ManifestField[]
}

interface ManifestMessage {
  type: string
  direction: "plugin_to_backend" | "backend_to_plugin" | "both"
  status: "active" | "reserved"
  pluginTransport: "typed" | "raw" | "absent"
  pluginDto?: string
  summary: string
  fields: ManifestField[]
  example: Record<string, unknown>
}

interface Manifest {
  version: number
  messages: ManifestMessage[]
}

if (!existsSync(manifestPath)) {
  throw new Error(`protocol manifest missing at ${manifestPath} — the contract cannot be checked`)
}

const manifest = JSON.parse(readFileSync(manifestPath, "utf8")) as Manifest
const byType = new Map(manifest.messages.map((message) => [message.type, message]))

/**
 * zod v4 has no public api for reading a schema's field types, so this walk
 * goes through the internal definition. introspection only, never validation.
 */
interface ZodNode {
  _zod?: { def?: Record<string, any> }
  shape?: Record<string, ZodNode>
  safeParse: (value: unknown) => { success: boolean; error?: { message?: string } }
}

const payloadSchemaMap = payloadSchemas as unknown as Record<string, ZodNode | undefined>

const WRAPPERS = new Set(["optional", "nullable", "default", "prefault"])

/** strips .optional()/.default()/.nullable() wrappers and reports whether the key may be omitted */
function unwrap(schema: ZodNode): { inner: ZodNode; optional: boolean } {
  let current = schema
  let optional = false
  for (;;) {
    const type = current._zod?.def?.type
    if (typeof type !== "string" || !WRAPPERS.has(type)) break
    optional = true
    current = current._zod?.def?.innerType as ZodNode
  }
  return { inner: current, optional }
}

interface SchemaKind {
  kind: ManifestField["type"] | "other"
  values?: string[]
  shape?: Record<string, ZodNode>
}

function kindOf(schema: ZodNode): SchemaKind {
  const def = schema._zod?.def ?? {}
  switch (def.type) {
    case "string":
      return { kind: "string" }
    case "number": {
      const checks: any[] = Array.isArray(def.checks) ? def.checks : []
      const isInt = checks.some((check) => check?.def?.format === "safeint" || check?.def?.check === "int")
      return { kind: isInt ? "integer" : "number" }
    }
    case "boolean":
      return { kind: "boolean" }
    case "enum":
      return { kind: "enum", values: Object.keys(def.entries ?? {}) }
    case "object":
      return { kind: "object", shape: schema.shape ?? {} }
    default:
      return { kind: "other" }
  }
}

/** every manifest field must exist in the schema with the same required-ness and type, and vice versa */
function assertFields(where: string, shape: Record<string, ZodNode>, fields: ManifestField[]): void {
  const names = fields.map((field) => field.name)
  expect(new Set(names).size, `${where}: duplicate field names in the manifest`).toBe(names.length)

  for (const key of Object.keys(shape)) {
    expect(names, `${where}: zod has field "${key}" that the manifest does not list`).toContain(key)
  }

  for (const field of fields) {
    const label = `${where}.${field.name}`
    const schema = shape[field.name]
    expect(schema, `${label}: manifest lists a field the zod schema does not have`).toBeDefined()
    if (!schema) continue

    const { inner, optional } = unwrap(schema)
    expect(optional, `${label}: required-ness drifted (manifest says required=${field.required})`).toBe(
      !field.required,
    )

    const info = kindOf(inner)
    if (field.type === "uuid" || field.type === "objectId" || field.type === "string") {
      expect(info.kind, `${label}: manifest says ${field.type}, zod says ${info.kind}`).toBe("string")
    } else if (field.type === "integer") {
      expect(info.kind, `${label}: manifest says integer, zod says ${info.kind}`).toBe("integer")
    } else if (field.type === "number" || field.type === "boolean") {
      expect(info.kind, `${label}: manifest says ${field.type}, zod says ${info.kind}`).toBe(field.type)
    } else if (field.type === "enum") {
      expect(info.kind, `${label}: manifest says enum, zod says ${info.kind}`).toBe("enum")
      expect([...(info.values ?? [])].sort(), `${label}: enum values drifted`).toEqual([...(field.values ?? [])].sort())
    } else {
      expect(info.kind, `${label}: manifest says object, zod says ${info.kind}`).toBe("object")
      expect(field.fields, `${label}: object field without a nested field list`).toBeDefined()
      assertFields(label, info.shape ?? {}, field.fields ?? [])
    }
  }
}

/** the fixture must be a schema-valid payload that exercises exactly the listed fields */
function assertExample(where: string, fields: ManifestField[], example: Record<string, unknown>): void {
  const names = fields.map((field) => field.name)
  for (const key of Object.keys(example)) {
    expect(names, `${where}: example carries "${key}" which is not a manifest field`).toContain(key)
  }
  for (const field of fields) {
    if (field.required) {
      expect(Object.hasOwn(example, field.name), `${where}: example is missing required field "${field.name}"`).toBe(
        true,
      )
    }
    if (field.type === "object" && Object.hasOwn(example, field.name)) {
      assertExample(
        `${where}.${field.name}`,
        field.fields ?? [],
        example[field.name] as Record<string, unknown>,
      )
    }
  }
}

describe("manifest structure", () => {
  test("every wire type is listed exactly once", () => {
    const types = manifest.messages.map((message) => message.type)
    expect(new Set(types).size, "duplicate message type in the manifest").toBe(types.length)
    expect([...types].sort()).toEqual([...MESSAGE_TYPES].sort())
  })

  test("entries use the documented vocabulary", () => {
    const fieldTypes = new Set(["uuid", "objectId", "string", "integer", "number", "boolean", "enum", "object"])
    for (const message of manifest.messages) {
      expect(["plugin_to_backend", "backend_to_plugin", "both"], `${message.type}: bad direction`).toContain(
        message.direction,
      )
      expect(["active", "reserved"], `${message.type}: bad status`).toContain(message.status)
      expect(["typed", "raw", "absent"], `${message.type}: bad pluginTransport`).toContain(message.pluginTransport)
      expect(message.summary.trim().length, `${message.type}: empty summary`).toBeGreaterThan(0)
      expect(message.fields.length, `${message.type}: no fields`).toBeGreaterThan(0)

      const walk = (fields: ManifestField[], where: string) => {
        for (const field of fields) {
          expect(fieldTypes, `${where}.${field.name}: unknown field type ${field.type}`).toContain(field.type)
          expect(typeof field.required, `${where}.${field.name}: required must be a boolean`).toBe("boolean")
          if (field.type === "enum") {
            expect((field.values ?? []).length, `${where}.${field.name}: enum without values`).toBeGreaterThan(0)
          }
          if (field.type === "object") walk(field.fields ?? [], `${where}.${field.name}`)
        }
      }
      walk(message.fields, message.type)
    }
  })

  test("a DTO only exists for a type the plugin actually carries", () => {
    for (const message of manifest.messages) {
      const dto = (message as { pluginDto?: string }).pluginDto
      if (dto !== undefined) {
        expect(["typed", "raw"], `${message.type}: pluginDto on a type the plugin does not carry`).toContain(
          message.pluginTransport,
        )
      }
    }
  })
})

describe("zod schemas match the manifest", () => {
  test("every manifest type has a zod payload schema", () => {
    for (const message of manifest.messages) {
      const schema = payloadSchemaMap[message.type]
      expect(schema, `${message.type}: no zod payload schema`).toBeDefined()
      if (schema) expect(kindOf(schema).kind, `${message.type}: payload schema is not an object`).toBe("object")
    }
  })

  test("no zod schema exists without a manifest entry", () => {
    const listed = new Set(manifest.messages.map((message) => message.type))
    for (const type of Object.keys(payloadSchemas)) {
      expect(listed.has(type), `${type}: zod schema is not in the protocol manifest`).toBe(true)
    }
  })

  test("field names, required-ness and types line up", () => {
    for (const message of manifest.messages) {
      const schema = payloadSchemaMap[message.type]
      if (!schema) continue
      assertFields(message.type, kindOf(schema).shape ?? {}, message.fields)
    }
  })
})

describe("direction sets match the manifest", () => {
  test("backend-to-plugin membership is exactly what the manifest says", () => {
    const backendSends = new Set<string>(BACKEND_TO_PLUGIN_TYPES)
    for (const message of manifest.messages) {
      const expected = message.direction !== "plugin_to_backend"
      expect(
        backendSends.has(message.type),
        `${message.type}: manifest direction ${message.direction} disagrees with BACKEND_TO_PLUGIN_TYPES`,
      ).toBe(expected)
    }
  })

  test("every backend-to-plugin entry is a known manifest type", () => {
    for (const type of BACKEND_TO_PLUGIN_TYPES) {
      expect(byType.has(type), `${type}: direction set carries an unlisted type`).toBe(true)
    }
  })
})

describe("example fixtures", () => {
  test("each example is complete and schema-valid", () => {
    for (const message of manifest.messages) {
      assertExample(message.type, message.fields, message.example)
      const schema = payloadSchemaMap[message.type]
      if (!schema) continue
      const parsed = schema.safeParse(message.example)
      if (!parsed.success) {
        throw new Error(`${message.type}: example does not satisfy the zod schema: ${parsed.error?.message}`)
      }
    }
  })

  test("required fields really are required by the schema", () => {
    for (const message of manifest.messages) {
      const schema = payloadSchemaMap[message.type]
      if (!schema) continue
      for (const field of message.fields.filter((candidate) => candidate.required)) {
        const without: Record<string, unknown> = { ...message.example }
        delete without[field.name]
        expect(
          schema.safeParse(without).success,
          `${message.type}: schema accepts a payload without required field "${field.name}"`,
        ).toBe(false)
      }
    }
  })
})
