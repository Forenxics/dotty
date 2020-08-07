package dotty.dokka

import org.jetbrains.dokka.base.translators.documentables.{DefaultPageCreator, PageContentBuilder, PageContentBuilder$DocumentableContentBuilder}
import org.jetbrains.dokka.base.signatures.SignatureProvider
import org.jetbrains.dokka.base.transformers.pages.comments.CommentsToContentConverter
import org.jetbrains.dokka.transformers.documentation.DocumentableToPageTranslator
import org.jetbrains.dokka.utilities.DokkaLogger
import org.jetbrains.dokka.model._
import org.jetbrains.dokka.pages._
import collection.JavaConverters._
import org.jetbrains.dokka.model.properties._
import org.jetbrains.dokka.base.transformers.documentables.CallableExtensions
import org.jetbrains.dokka.DokkaConfiguration$DokkaSourceSet
import org.jetbrains.dokka.base.resolvers.anchors._



class ScalaPageCreator(
    commentsToContentConverter: CommentsToContentConverter,
    signatureProvider: SignatureProvider,
    val logger: DokkaLogger
) extends DefaultPageCreator(commentsToContentConverter, signatureProvider, logger) {
    override def pageForClasslike(c: DClasslike): ClasslikePageNode = {
        val res = super.pageForClasslike(c)
        def addCompanionObjectPage(co: DClasslike, defContent: ClasslikePageNode): ClasslikePageNode = {
            val page = pageForClasslike(co)
            defContent.modified(
                defContent.getName,
                defContent.getContent,
                defContent.getDri,
                defContent.getEmbeddedResources,
                (defContent.getChildren.asScala ++ List(
                    page.modified(
                        page.getName + "$",
                        page.getContent,
                        page.getDri,
                        page.getEmbeddedResources,
                        page.getChildren
                    )
                )).asJava
            )
        }

        def addExtensionMethodPages(clazz: DClass, defContent: ClasslikePageNode): ClasslikePageNode = {
            val extensionPages = clazz.getExtra.getMap.asScala.values
                .collect { case e: CallableExtensions => e.getExtensions.asScala }
                .flatten
                .collect { case f: DFunction => f }
                .map(pageForFunction(_))
                .map(page =>
                    page.modified(
                        "extension_" + page.getName,
                        page.getContent,
                        page.getDri,
                        page.getEmbeddedResources,
                        page.getChildren
                    )
                )

            defContent.modified(
                defContent.getName,
                defContent.getContent,
                defContent.getDri,
                defContent.getEmbeddedResources,
                (defContent.getChildren.asScala ++ extensionPages).asJava
            )
        }
        
        c match {
            case clazz: DClass => 
                val op1 = clazz.get(ClasslikeExtension).companion.fold(res)(addCompanionObjectPage(_, res))
                val op2 = addExtensionMethodPages(clazz, op1)
                op2
            case _ => res
        }
    }

    def insertCompanionObject(clazz: DClass, defContent: ContentGroup): ContentGroup = {
        def companionObjectContent(co: DClasslike): Function1[PageContentBuilder#DocumentableContentBuilder, kotlin.Unit] = builder => {
            group(builder)(builder => {
                    sourceSetDependentHint(builder)(
                        builder => {
                            builder.unaryPlus(builder.buildSignature(co))
                            kotlin.Unit.INSTANCE
                        }
                    )
                    kotlin.Unit.INSTANCE
                }, kind = ContentKind.Cover
            )
            kotlin.Unit.INSTANCE
        }

        clazz.get(ClasslikeExtension).companion.fold(defContent)(co => {
                    val addedContent = PageContentBuilder(commentsToContentConverter, signatureProvider, logger).contentFor(clazz)(companionObjectContent(co))
                    val newChildren = List(defContent.getChildren.asScala.head) ++ List(addedContent) ++ defContent.getChildren.asScala.tail
                    ContentGroup(
                        newChildren.asJava,
                        defContent.getDci,
                        defContent.getSourceSets,
                        defContent.getStyle,
                        defContent.getExtra
                    )
        })
    }

    def insertCustomExtensionTab(clazz: DClass, defContent: ContentGroup): ContentGroup = {
        val content = getContentGroupWithParents(defContent, p => p.getStyle.asScala.contains(ContentStyle.TabbedContent))
        val addedContent = PageContentBuilder(commentsToContentConverter, signatureProvider, logger).contentFor(clazz)(builder => {
            groupingBlock(
                builder,
                "Extensions",
                clazz.get(ClasslikeExtension).extensions.map(e => e.extendedSymbol -> e.extensions).sortBy(_._2.size),
                (builder, receiver) => {
                    group(builder)(builder => {
                        builder.unaryPlus(builder.buildSignature(receiver))
                        kotlin.Unit.INSTANCE
                    })
                },
                (builder, elem) => {
                    link(builder, elem.getName, elem.getDri)(kind = ContentKind.Main)
                    sourceSetDependentHint(builder)( builder =>
                        {
                            contentForBrief(builder, elem)
                            builder.unaryPlus(builder.buildSignature(elem))
                            kotlin.Unit.INSTANCE
                        },
                        dri = Set(elem.getDri).asJava,
                        sourceSets = elem.getSourceSets,
                        kind = ContentKind.SourceSetDependentHint,
                        styles = Set().asJava
                    )
                    kotlin.Unit.INSTANCE
                }
            )(
                kind = ContentKind.Main,
                sourceSets = builder.getMainSourcesetData.asScala.toSet,
                styles = Set(),
                extra = PropertyContainer.Companion.empty().plus(SimpleAttr.Companion.header("Extensions")),
                false,
                true,
                Nil,
                false,
                true
            )
            kotlin.Unit.INSTANCE
        })
        val modifiedContent = content(0).copy(
            (content(0).getChildren.asScala ++ List(addedContent)).asJava,
            content(0).getDci,
            content(0).getSourceSets,
            content(0).getStyle,
            content(0).getExtra
        )
        modifyContentGroup(content, modifiedContent)
    }

    override def contentForClasslike(c: DClasslike): ContentGroup = {
        val defaultContent = super.contentForClasslike(c)
      
        c match{
            case clazz: DClass =>
                val op1 = insertCompanionObject(clazz, defaultContent)
                insertCustomExtensionTab(clazz, op1)
            case _ => defaultContent
        }
    }

    private def modifyContentGroup(originalContentNodeWithParents: Seq[ContentGroup], modifiedContentNode: ContentGroup): ContentGroup =
        originalContentNodeWithParents match {
            case (head: ContentGroup) :: (tail: Seq[ContentGroup]) => tail match {
                case (tailHead: ContentGroup) :: (tailTail: Seq[ContentGroup]) =>
                    val newChildren = tailHead.getChildren.asScala.map(c => if c != head then c else modifiedContentNode)
                    modifyContentGroup(
                        tailTail,
                        tailHead.copy(
                            newChildren.asJava,
                            tailHead.getDci,
                            tailHead.getSourceSets,
                            tailHead.getStyle,
                            tailHead.getExtra
                        )
                    )
                case _ => head
            }
            case _ => modifiedContentNode
        }

    private def getContentGroupWithParents(root: ContentGroup, condition: ContentGroup => Boolean): Seq[ContentGroup] = {
        def getFirstMatch(list: List[ContentNode]): Seq[ContentGroup] = list match {
            case (head: ContentNode) :: (tail: List[ContentNode]) => head match {
                case g: ContentGroup => 
                    val res = getContentGroupWithParents(g, condition)
                    if(!res.isEmpty) res
                    else getFirstMatch(tail)
                case _ => getFirstMatch(tail)
            }
                
            case _ => Seq()
        }
        if(condition(root)) Seq(root)
        else {
            val res = getFirstMatch(root.getChildren.asScala.toList)
            if(!res.isEmpty) res ++ Seq(root)
            else Seq()
        }
    }

    private def groupingBlock[G, T <: Documentable](
        builder: PageContentBuilder#DocumentableContentBuilder,
        name: String,
        elements: List[T],
        groupingFunc: T => G,
        groupSplitterFunc: (PageContentBuilder#DocumentableContentBuilder, G) => Unit,
        elementFunc: (PageContentBuilder#DocumentableContentBuilder, T) => Unit
    )(
        kind: Kind = ContentKind.Main,
        sourceSets: Set[DokkaConfiguration$DokkaSourceSet] = builder.getMainSourcesetData.asScala.toSet,
        styles: Set[Style] = builder.getMainStyles.asScala.toSet,
        extra: PropertyContainer[ContentNode] = builder.getMainExtra,
        renderWhenEmpty: Boolean = false,
        needsSorting: Boolean = true,
        headers: List[ContentGroup] = Nil,
        needsAnchors: Boolean = false,
        omitSplitterOnSingletons: Boolean = false
    ): Unit = {
        val grouped = elements.groupBy(groupingFunc).toList
        groupingBlock(
            builder, 
            name, 
            grouped, 
            groupSplitterFunc, 
            elementFunc
        )(
            kind, 
            sourceSets, 
            styles, 
            extra, 
            renderWhenEmpty, 
            needsSorting, 
            headers, 
            needsAnchors,
            omitSplitterOnSingletons
        )
    }

    private def groupingBlock[A, T <: Documentable, G <: List[(A, List[T])]](
        builder: PageContentBuilder#DocumentableContentBuilder,
        name: String,
        elements: G,
        groupSplitterFunc: (PageContentBuilder#DocumentableContentBuilder, A) => Unit,
        elementFunc: (PageContentBuilder#DocumentableContentBuilder, T) => Unit
    )(
        kind: Kind,
        sourceSets: Set[DokkaConfiguration$DokkaSourceSet],
        styles: Set[Style],
        extra: PropertyContainer[ContentNode],
        renderWhenEmpty: Boolean,
        needsSorting: Boolean,
        headers: List[ContentGroup],
        needsAnchors: Boolean,
        omitSplitterOnSingletons: Boolean
    ): Unit = if (renderWhenEmpty || !elements.isEmpty) {     
            header(builder, 3, name)(kind = kind)
            group(builder)(builder =>
            {
                elements.foreach((a, elems) => {
                    if(elems.size > 1 || !omitSplitterOnSingletons) groupSplitterFunc(builder, a)
                    builder.unaryPlus(
                        ContentTable(
                            headers.asJava,
                            (if(needsSorting) then elems.sortBy(_.getName) else elems)
                                .map( elem => {
                                    //TODO: There's problem with using extra property containers from Dokka in Scala
                                    //val newExtra = if(needsAnchors) then extra.plus(SymbolAnchorHint) else extra
                                    val newExtra = extra
                                    builder.buildGroup(Set(elem.getDri).asJava, elem.getSourceSets, kind, styles.asJava, newExtra, bdr => { 
                                        elementFunc(bdr, elem)
                                        kotlin.Unit.INSTANCE
                                    })
                                }).asJava,
                            DCI(builder.getMainDRI, kind),
                            sourceSets.asJava, styles.asJava, extra
                        )
                    )
                })
                kotlin.Unit.INSTANCE
            },
            styles = Set(ContentStyle.WithExtraAttributes),
            extra = extra
        )
    }

    private def contentForBrief(builder: PageContentBuilder#DocumentableContentBuilder, d: Documentable) = d.getSourceSets.asScala.foreach( ss =>
        d.getDocumentation.asScala.toMap.get(ss).flatMap(_.getChildren.asScala.headOption).map(_.getRoot).map( dt =>
            group(builder)(builder => 
                {
                    builder.comment(dt, ContentKind.Comment, builder.getMainSourcesetData, builder.getMainStyles, builder.getMainExtra)
                    kotlin.Unit.INSTANCE
                },
            sourceSets = Set(ss),
            kind = ContentKind.BriefComment
            )
        )
    )
        

}