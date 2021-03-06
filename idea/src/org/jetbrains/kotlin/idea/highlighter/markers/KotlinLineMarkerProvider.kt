/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.highlighter.markers

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.*
import com.intellij.codeInsight.daemon.impl.LineMarkerNavigator
import com.intellij.codeInsight.daemon.impl.MarkerType
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.navigation.ListBackgroundUpdaterTask
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.idea.KotlinIcons
import org.jetbrains.kotlin.idea.caches.lightClasses.KtFakeLightClass
import org.jetbrains.kotlin.idea.caches.lightClasses.KtFakeLightMethod
import org.jetbrains.kotlin.idea.caches.project.implementedDescriptors
import org.jetbrains.kotlin.idea.caches.project.implementingDescriptors
import org.jetbrains.kotlin.idea.caches.resolve.findModuleDescriptor
import org.jetbrains.kotlin.idea.core.isInheritable
import org.jetbrains.kotlin.idea.core.isOverridable
import org.jetbrains.kotlin.idea.core.toDescriptor
import org.jetbrains.kotlin.idea.editor.fixers.startLine
import org.jetbrains.kotlin.idea.presentation.DeclarationByModuleRenderer
import org.jetbrains.kotlin.idea.search.declarationsSearch.toPossiblyFakeLightMethods
import org.jetbrains.kotlin.idea.util.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getPrevSiblingIgnoringWhitespaceAndComments
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.Icon
import javax.swing.ListCellRenderer

class KotlinLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (DaemonCodeAnalyzerSettings.getInstance().SHOW_METHOD_SEPARATORS) {
            if (element.canHaveSeparator()) {
                val prevSibling = element.getPrevSiblingIgnoringWhitespaceAndComments()
                if (prevSibling.canHaveSeparator() &&
                    (element.wantsSeparator() || prevSibling?.wantsSeparator() == true)
                ) {
                    return createLineSeparatorByElement(element)
                }
            }
        }

        return null
    }

    private fun PsiElement?.canHaveSeparator() =
        this is KtFunction || this is KtClassInitializer || (this is KtProperty && !isLocal)

    private fun PsiElement.wantsSeparator() = StringUtil.getLineBreakCount(text) > 0

    private fun createLineSeparatorByElement(element: PsiElement): LineMarkerInfo<PsiElement> {
        val anchor = PsiTreeUtil.getDeepestFirst(element)

        val info = LineMarkerInfo(anchor, anchor.textRange, null, Pass.LINE_MARKERS, null, null, GutterIconRenderer.Alignment.RIGHT)
        info.separatorColor = EditorColorsManager.getInstance().globalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
        info.separatorPlacement = SeparatorPlacement.TOP
        return info
    }

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<LineMarkerInfo<*>>) {
        if (elements.isEmpty()) return

        val first = elements.first()
        if (DumbService.getInstance(first.project).isDumb || !ProjectRootsUtil.isInProjectOrLibSource(first)) return

        val functions = hashSetOf<KtNamedFunction>()
        val properties = hashSetOf<KtNamedDeclaration>()
        val declarations = hashSetOf<KtNamedDeclaration>()

        for (leaf in elements) {
            ProgressManager.checkCanceled()
            val element = leaf.parent as? KtNamedDeclaration ?: continue
            if (!declarations.add(element)) continue

            when (element) {
                is KtClass -> {
                    collectInheritedClassMarker(element, result)
                    collectHighlightingColorsMarkers(element, result)
                }
                is KtNamedFunction -> {
                    functions.add(element)
                    collectSuperDeclarationMarkers(element, result)
                }
                is KtProperty -> {
                    properties.add(element)
                    collectSuperDeclarationMarkers(element, result)
                }
                is KtParameter -> {
                    if (element.hasValOrVar()) {
                        properties.add(element)
                        collectSuperDeclarationMarkers(element, result)
                    }
                }
            }
            collectMultiplatformMarkers(element, result)
        }

        collectOverriddenFunctions(functions, result)
        collectOverriddenPropertyAccessors(properties, result)
    }
}

private val OVERRIDING_MARK: Icon = AllIcons.Gutter.OverridingMethod
private val IMPLEMENTING_MARK: Icon = AllIcons.Gutter.ImplementingMethod
private val OVERRIDDEN_MARK: Icon = AllIcons.Gutter.OverridenMethod
private val IMPLEMENTED_MARK: Icon = AllIcons.Gutter.ImplementedMethod

data class NavigationPopupDescriptor(
    val targets: Collection<NavigatablePsiElement>,
    val title: String,
    val findUsagesTitle: String,
    val renderer: ListCellRenderer<*>,
    val updater: ListBackgroundUpdaterTask? = null
) {
    fun showPopup(e: MouseEvent?) {
        PsiElementListNavigator.openTargets(e, targets.toTypedArray(), title, findUsagesTitle, renderer, updater)
    }
}

interface TestableLineMarkerNavigator {
    fun getTargetsPopupDescriptor(element: PsiElement?): NavigationPopupDescriptor?
}

private val SUBCLASSED_CLASS = MarkerType(
    "SUBCLASSED_CLASS",
    { getPsiClass(it)?.let(::getSubclassedClassTooltip) },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            getPsiClass(element)?.let {
                MarkerType.navigateToSubclassedClass(e, it, DeclarationByModuleRenderer())
            }
        }
    })

private val OVERRIDDEN_FUNCTION = object : MarkerType(
    "OVERRIDDEN_FUNCTION",
    { getPsiMethod(it)?.let(::getOverriddenMethodTooltip) },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            buildNavigateToOverriddenMethodPopup(e, element)?.showPopup(e)
        }
    }) {

    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?) = buildNavigateToOverriddenMethodPopup(null, element)
        }
    }
}

private val OVERRIDDEN_PROPERTY = object : MarkerType(
    "OVERRIDDEN_PROPERTY",
    { it?.let { getOverriddenPropertyTooltip(it.parent as KtNamedDeclaration) } },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            buildNavigateToPropertyOverriddenDeclarationsPopup(e, element)?.showPopup(e)
        }
    }) {

    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?) = buildNavigateToPropertyOverriddenDeclarationsPopup(null, element)
        }
    }
}

private val PsiElement.markerDeclaration
    get() = (this as? KtDeclaration) ?: (parent as? KtDeclaration)

private val PLATFORM_ACTUAL = MarkerType(
    "PLATFORM_ACTUAL",
    { element -> element?.markerDeclaration?.let { getPlatformActualTooltip(it) } },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            element?.markerDeclaration?.let { navigateToPlatformActual(e, it) }
        }
    }
)

private val EXPECTED_DECLARATION = MarkerType(
    "EXPECTED_DECLARATION",
    { element -> element?.markerDeclaration?.let { getExpectedDeclarationTooltip(it) } },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            element?.markerDeclaration?.let { navigateToExpectedDeclaration(e, it) }
        }
    }
)

private fun isImplementsAndNotOverrides(
    descriptor: CallableMemberDescriptor,
    overriddenMembers: Collection<CallableMemberDescriptor>
): Boolean {
    return descriptor.modality != Modality.ABSTRACT && overriddenMembers.all { it.modality == Modality.ABSTRACT }
}

private fun collectSuperDeclarationMarkers(declaration: KtDeclaration, result: MutableCollection<LineMarkerInfo<*>>) {
    assert(declaration is KtNamedFunction || declaration is KtProperty || declaration is KtParameter)

    if (!declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return

    val resolveWithParents = resolveDeclarationWithParents(declaration)
    if (resolveWithParents.overriddenDescriptors.isEmpty()) return

    val implements = isImplementsAndNotOverrides(resolveWithParents.descriptor!!, resolveWithParents.overriddenDescriptors)

    val anchor = (declaration as? KtNamedDeclaration)?.nameIdentifier ?: declaration

    // NOTE: Don't store descriptors in line markers because line markers are not deleted while editing other files and this can prevent
    // clearing the whole BindingTrace.

    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        if (implements) IMPLEMENTING_MARK else OVERRIDING_MARK,
        Pass.LINE_MARKERS,
        SuperDeclarationMarkerTooltip,
        SuperDeclarationMarkerNavigationHandler(),
        GutterIconRenderer.Alignment.RIGHT
    )
    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        if (declaration is KtNamedFunction) "Go to super method" else "Go to super property",
        IdeActions.ACTION_GOTO_SUPER
    )
    result.add(lineMarkerInfo)
}

private fun collectInheritedClassMarker(element: KtClass, result: MutableCollection<LineMarkerInfo<*>>) {
    if (!element.isInheritable()) {
        return
    }

    val lightClass = element.toLightClass() ?: KtFakeLightClass(element)

    if (ClassInheritorsSearch.search(lightClass, false).findFirst() == null) return

    val anchor = element.nameIdentifier ?: element

    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        if (element.isInterface()) IMPLEMENTED_MARK else OVERRIDDEN_MARK,
        Pass.LINE_MARKERS,
        SUBCLASSED_CLASS.tooltip,
        SUBCLASSED_CLASS.navigationHandler,
        GutterIconRenderer.Alignment.RIGHT
    )
    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        if (element.isInterface()) "Go to implementations" else "Go to subclasses",
        IdeActions.ACTION_GOTO_IMPLEMENTATION
    )
    result.add(lineMarkerInfo)
}

private fun collectOverriddenPropertyAccessors(
    properties: Collection<KtNamedDeclaration>,
    result: MutableCollection<LineMarkerInfo<*>>
) {
    val mappingToJava = HashMap<PsiElement, KtNamedDeclaration>()
    for (property in properties) {
        if (property.isOverridable()) {
            property.toPossiblyFakeLightMethods().forEach { mappingToJava[it] = property }
            mappingToJava[property] = property
        }
    }

    val classes = collectContainingClasses(mappingToJava.keys.filterIsInstance<PsiMethod>())

    for (property in getOverriddenDeclarations(mappingToJava, classes)) {
        ProgressManager.checkCanceled()

        val anchor = (property as? PsiNameIdentifierOwner)?.nameIdentifier ?: property

        val lineMarkerInfo = LineMarkerInfo(
            anchor,
            anchor.textRange,
            if (isImplemented(property)) IMPLEMENTED_MARK else OVERRIDDEN_MARK,
            Pass.LINE_MARKERS,
            OVERRIDDEN_PROPERTY.tooltip,
            OVERRIDDEN_PROPERTY.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT
        )
        NavigateAction.setNavigateAction(
            lineMarkerInfo,
            "Go to overridden properties",
            IdeActions.ACTION_GOTO_IMPLEMENTATION
        )
        result.add(lineMarkerInfo)
    }
}

private val KtNamedDeclaration.expectOrActualAnchor
    get() =
        nameIdentifier ?: when (this) {
            is KtConstructor<*> -> getConstructorKeyword() ?: getValueParameterList()?.leftParenthesis
            is KtObjectDeclaration -> getObjectKeyword()
            else -> null
        } ?: this

private fun collectMultiplatformMarkers(
    declaration: KtNamedDeclaration,
    result: MutableCollection<LineMarkerInfo<*>>
) {
    when {
        declaration.isExpectDeclaration() -> collectActualMarkers(declaration, result)
        declaration.isEffectivelyActual() -> collectExpectedMarkers(declaration, result)
    }
}

private fun Document.areAnchorsOnOneLine(
    first: KtNamedDeclaration,
    second: KtNamedDeclaration?
): Boolean {
    if (second == null) return false
    val firstAnchor = first.expectOrActualAnchor
    val secondAnchor = second.expectOrActualAnchor
    return firstAnchor.startLine(this) == secondAnchor.startLine(this)
}

private fun KtNamedDeclaration.requiresNoMarkers(
    document: Document? = PsiDocumentManager.getInstance(project).getDocument(containingFile)
): Boolean {
    when (this) {
        is KtPrimaryConstructor -> {
            return true
        }
        is KtParameter,
        is KtEnumEntry -> if (document?.areAnchorsOnOneLine(this, containingClassOrObject) == true) {
            return true
        }
    }
    return false
}

internal fun KtDeclaration.findMarkerBoundDeclarations(): List<KtNamedDeclaration> {
    if (this !is KtClass) return emptyList()
    val result = mutableListOf<KtNamedDeclaration>()
    val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
    result += primaryConstructor?.valueParameters?.filter { it.hasValOrVar() && it.requiresNoMarkers(document) }.orEmpty()
    if (this.isEnum()) {
        result += this.body?.enumEntries?.filter { it.requiresNoMarkers(document) }.orEmpty()
    }
    return result
}

private fun collectActualMarkers(
    declaration: KtNamedDeclaration,
    result: MutableCollection<LineMarkerInfo<*>>
) {
    if (declaration.requiresNoMarkers()) return

    val descriptor = declaration.toDescriptor() as? MemberDescriptor ?: return
    val commonModuleDescriptor = declaration.containingKtFile.findModuleDescriptor()

    if (commonModuleDescriptor.implementingDescriptors.none { it.hasActualsFor(descriptor) }) return

    val anchor = declaration.expectOrActualAnchor

    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        KotlinIcons.ACTUAL,
        Pass.LINE_MARKERS,
        PLATFORM_ACTUAL.tooltip,
        PLATFORM_ACTUAL.navigationHandler,
        GutterIconRenderer.Alignment.RIGHT
    )
    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        "Go to actual declarations",
        IdeActions.ACTION_GOTO_IMPLEMENTATION
    )
    result.add(lineMarkerInfo)
}

private fun collectExpectedMarkers(
    declaration: KtNamedDeclaration,
    result: MutableCollection<LineMarkerInfo<*>>
) {
    if (declaration.requiresNoMarkers()) return

    val descriptor = declaration.toDescriptor() as? MemberDescriptor ?: return
    val platformModuleDescriptor = declaration.containingKtFile.findModuleDescriptor()
    if (!platformModuleDescriptor.implementedDescriptors.any { it.hasDeclarationOf(descriptor) }) return

    val anchor = declaration.expectOrActualAnchor

    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        KotlinIcons.EXPECT,
        Pass.LINE_MARKERS,
        EXPECTED_DECLARATION.tooltip,
        EXPECTED_DECLARATION.navigationHandler,
        GutterIconRenderer.Alignment.RIGHT
    )
    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        "Go to expected declaration",
        null
    )
    result.add(lineMarkerInfo)
}

private fun collectOverriddenFunctions(functions: Collection<KtNamedFunction>, result: MutableCollection<LineMarkerInfo<*>>) {
    val mappingToJava = HashMap<PsiElement, KtNamedFunction>()
    for (function in functions) {
        if (function.isOverridable()) {
            val method = LightClassUtil.getLightClassMethod(function) ?: KtFakeLightMethod.get(function)
            if (method != null) {
                mappingToJava[method] = function
            }
            mappingToJava[function] = function
        }
    }

    val classes = collectContainingClasses(mappingToJava.keys.filterIsInstance<PsiMethod>())

    for (function in getOverriddenDeclarations(mappingToJava, classes)) {
        ProgressManager.checkCanceled()

        val anchor = function.nameIdentifier ?: function

        val lineMarkerInfo = LineMarkerInfo(
            anchor,
            anchor.textRange,
            if (isImplemented(function)) IMPLEMENTED_MARK else OVERRIDDEN_MARK,
            Pass.LINE_MARKERS, OVERRIDDEN_FUNCTION.tooltip,
            OVERRIDDEN_FUNCTION.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT
        )
        NavigateAction.setNavigateAction(
            lineMarkerInfo,
            "Go to overridden methods",
            IdeActions.ACTION_GOTO_IMPLEMENTATION
        )
        result.add(lineMarkerInfo)
    }
}
