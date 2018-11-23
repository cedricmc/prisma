package com.prisma.deploy.migration

import com.prisma.deploy.connector.{EmptyDatabaseIntrospectionInferrer, FieldRequirementsInterface, ForeignKey, Tables}
import com.prisma.deploy.connector.postgres.database.DatabaseIntrospectionInferrerImpl
import com.prisma.deploy.migration.inference.{MigrationStepsInferrer, SchemaInferrer}
import com.prisma.deploy.schema.mutations.{DeployMutation, DeployMutationInput, MutationError, MutationSuccess}
import com.prisma.deploy.specutils.DeploySpecBase
import com.prisma.shared.models.ConnectorCapability.{IntIdCapability, UuidIdCapability}
import com.prisma.shared.models.{ConnectorCapabilities, Project, Schema}
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{Matchers, WordSpecLike}

class MigrationsSpec extends WordSpecLike with Matchers with DeploySpecBase {

  val name      = this.getClass.getSimpleName
  val stage     = "default"
  val serviceId = testDependencies.projectIdEncoder.toEncodedString(name, stage)
  val initialDataModel =
    """
      |type A {
      |  id: ID! @id
      |}
    """.stripMargin
  val inspector        = deployConnector.testFacilities.inspector
  var project: Project = Project(id = serviceId, schema = Schema.empty)

  import system.dispatcher

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    setup()
  }

  val TI = com.prisma.shared.models.TypeIdentifier

  "adding a scalar field should work" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  string: String
        |  float: Float
        |  boolean: Boolean
        |  enum: MyEnum
        |  json: Json
        |  dateTime: DateTime
        |  cuid: ID
        |  int: Int
        |}
        |enum MyEnum {
        |  A,
        |  B
        |}
      """.stripMargin

    val result = deploy(dataModel)
    val table  = result.table_!("A")

    table.columns.filter(_.name != "id").foreach { column =>
      column.isRequired should be(false)
    }

    table.column_!("string").typeIdentifier should be(TI.String)
    table.column_!("float").typeIdentifier should be(TI.Float)
    table.column_!("boolean").typeIdentifier should be(TI.Boolean)
    table.column_!("enum").typeIdentifier should be(TI.String)
    table.column_!("json").typeIdentifier should be(TI.String)
    table.column_!("dateTime").typeIdentifier should be(TI.DateTime)
    table.column_!("cuid").typeIdentifier should be(TI.String)
    table.column_!("int").typeIdentifier should be(TI.Int)

//    column.tpe should be("text") // TODO: make specific type assertions vendor specific
  }

  "adding a required scalar field should work" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  field: String!
        |}
      """.stripMargin

    val result = deploy(dataModel)

    val column = result.table_!("A").column_!("field")
    column.isRequired should be(true)
  }

  "adding a plain many to many relation should result in our plain relation table" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  bs: [B]
        |}
        |
        |type B {
        |  id: ID! @id
        |  as: [A]
        |}
      """.stripMargin

    val result        = deploy(dataModel)
    val relationTable = result.table_!("AToB")
    relationTable.columns should have(size(3))
    relationTable.column_!("id").typeIdentifier should be(TI.String)
    val aColumn = relationTable.column_!("A")
    aColumn.typeIdentifier should be(TI.String)
    aColumn.foreignKey should be(Some(ForeignKey("A", "id")))
    val bColumn = relationTable.column_!("B")
    bColumn.typeIdentifier should be(TI.String)
    bColumn.foreignKey should be(Some(ForeignKey("B", "id")))
  }

  "adding an inline relation should result in a foreign key in the model table" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  b: B @relation(link: INLINE)
        |}
        |
        |type B {
        |  id: ID! @id
        |}
      """.stripMargin

    val result = deploy(dataModel)

    val bColumn = result.table_!("A").column_!("b")
    bColumn.foreignKey should equal(Some(ForeignKey("B", "id")))
    bColumn.typeIdentifier should be(TI.String)
  }

  "changing the db name of an inline relation field must work" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  b: B @relation(link: INLINE) @db(name: "b_column")
        |}
        |
        |type B {
        |  id: ID! @id
        |}
      """.stripMargin

    val result  = deploy(dataModel)
    val bColumn = result.table_!("A").column_!("b_column")
    bColumn.foreignKey should equal(Some(ForeignKey("B", "id")))
  }

  "adding an inline relation to an model that has as id field of a non normal type" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  b: B @relation(link: INLINE)
        |  c: C @relation(link: INLINE)
        |}
        |
        |type B {
        |  id: Int! @id
        |}
        |
        |type C {
        |  id: UUID! @id
        |}
      """.stripMargin

    val result = deploy(dataModel, ConnectorCapabilities(IntIdCapability, UuidIdCapability))

    val bColumn = result.table_!("A").column_!("b")
    bColumn.foreignKey should equal(Some(ForeignKey("B", "id")))
    bColumn.typeIdentifier should be(TI.Int)

    val cColumn = result.table_!("A").column_!("c")
    cColumn.foreignKey should equal(Some(ForeignKey("C", "id")))
    cColumn.typeIdentifier should be(TI.UUID)
  }

  "adding an inline self relation should add the relation link in the right column" in {
    val dataModel =
      """
        |type A {
        |  id: ID! @id
        |  a1: A @relation(name: "Selfie")
        |  a2: A @relation(name: "Selfie", link: INLINE)
        |  b1: A @relation(name: "Selfie2", link: INLINE)
        |  b2: A @relation(name: "Selfie2")
        |}
      """.stripMargin
    // testing with 2 self relations to make sure choosing the column for the foreign key is not due to lexicographic order
    val result = deploy(dataModel)
    result.table_!("A").column_!("a2").foreignKey should equal(Some(ForeignKey("A", "id")))
    result.table_!("A").column_!("b1").foreignKey should equal(Some(ForeignKey("A", "id")))
  }

  def setup() = {
    val idAsString = testDependencies.projectIdEncoder.toEncodedString(name, stage)
    deployConnector.deleteProjectDatabase(idAsString).await()
    server.addProject(name, stage)
    deploy(initialDataModel, ConnectorCapabilities.empty)
    project = testDependencies.projectPersistence.load(serviceId).await.get
  }

  def deploy(dataModel: String, capabilities: ConnectorCapabilities = ConnectorCapabilities.empty): Tables = {
    val input = DeployMutationInput(
      clientMutationId = None,
      name = name,
      stage = stage,
      types = dataModel,
      dryRun = None,
      force = None,
      secrets = Vector.empty,
      functions = Vector.empty
    )
    val mutation = DeployMutation(
      args = input,
      project = project,
      schemaInferrer = SchemaInferrer(capabilities),
      migrationStepsInferrer = MigrationStepsInferrer(),
      schemaMapper = SchemaMapper,
      migrationPersistence = testDependencies.migrationPersistence,
      projectPersistence = testDependencies.projectPersistence,
      migrator = testDependencies.migrator,
      functionValidator = testDependencies.functionValidator,
      invalidationPublisher = testDependencies.invalidationPublisher,
      capabilities = capabilities,
      clientDbQueries = deployConnector.clientDBQueries(project),
      databaseIntrospectionInferrer = EmptyDatabaseIntrospectionInferrer,
      fieldRequirements = FieldRequirementsInterface.empty,
      isActive = true
    )

    val result = mutation.execute.await
    result match {
      case MutationSuccess(result) =>
        if (result.errors.nonEmpty) {
          sys.error(s"Deploy returned unexpected errors: ${result.errors}")
        } else {
          inspect
        }
      case MutationError =>
        sys.error("Deploy returned an unexpected error")
    }
  }

  def inspect: Tables = {
    deployConnector.testFacilities.inspector.inspect(serviceId).await()
  }
}
