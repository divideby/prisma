package com.prisma.api.mutations.mutations

import com.prisma.api.ApiDependencies
import com.prisma.api.database.DataResolver
import com.prisma.api.database.mutactions.{MutactionGroup, TransactionMutaction}
import com.prisma.api.mutations._
import com.prisma.api.mutations.mutations.CascadingDeletes.Path
import com.prisma.shared.models.{Model, Project}
import cool.graph.cuid.Cuid
import sangria.schema

import scala.concurrent.Future

case class Upsert(
    model: Model,
    project: Project,
    args: schema.Args,
    dataResolver: DataResolver,
    allowSettingManagedFields: Boolean = false
)(implicit apiDependencies: ApiDependencies)
    extends SingleItemClientMutation {

  import apiDependencies.system.dispatcher

  val outerWhere: NodeSelector = CoolArgs(args.raw).extractNodeSelectorFromWhereField(model)

  val idOfNewItem: String       = Cuid.createCuid()
  val createWhere: NodeSelector = NodeSelector.forId(model, idOfNewItem)
  val updateArgs: CoolArgs      = CoolArgs(args.raw).updateArgumentsAsCoolArgs.generateNonListUpdateArgs(model)
  val updatedWhere: NodeSelector = updateArgs.raw.get(outerWhere.field.name) match {
    case Some(_) => updateArgs.extractNodeSelector(model)
    case None    => outerWhere
  }

  val path = Path.empty(outerWhere)

  override def prepareMutactions(): Future[List[MutactionGroup]] = {

    val sqlMutactions        = SqlMutactions(dataResolver).getMutactionsForUpsert(path, createWhere, updatedWhere, CoolArgs(args.raw)).toList
    val transactionMutaction = TransactionMutaction(sqlMutactions, dataResolver)
//    val subscriptionMutactions = SubscriptionEvents.extractFromSqlMutactions(project, mutationId, sqlMutactions).toList
//    val sssActions             = ServerSideSubscription.extractFromMutactions(project, sqlMutactions, requestId = "").toList

    Future(
      List(
        MutactionGroup(mutactions = List(transactionMutaction), async = false)
//    ,  MutactionGroup(mutactions = sssActions ++ subscriptionMutactions, async = true)
      ))
//    val transaction = TransactionMutaction(List(upsert), dataResolver)
//    Future.successful(List(MutactionGroup(List(transaction), async = false)))
  }

  override def getReturnValue: Future[ReturnValueResult] = {

    val uniques = Vector(createWhere, updatedWhere)
    dataResolver.resolveByUniques(model, uniques).map { items =>
      items.headOption match {
        case Some(item) => ReturnValue(item)
        case None       => sys.error("Could not find an item after an Upsert. This should not be possible.") // Todo: what should we do here?
      }
    }
  }
}