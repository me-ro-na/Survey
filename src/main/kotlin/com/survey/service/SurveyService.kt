package com.survey.service

import com.survey.dto.ResponseRequest
import com.survey.dto.SurveyItemRequest
import com.survey.dto.SurveyOptionRequest
import com.survey.exception.RequiredFieldMissingException
import com.survey.exception.ResponseOptionNotAllowedException
import com.survey.exception.ResponseRequiredFieldMissingException
import com.survey.exception.ResponseSurveyNotFoundException
import com.survey.exception.ResponseTypeMismatchException
import com.survey.exception.SurveyItemCountException
import com.survey.exception.SurveyNotFoundException
import com.survey.exception.SurveyOptionsMissingException
import com.survey.exception.SurveyTypeConflictException
import com.survey.exception.SurveyUpdateFieldMissingException
import com.survey.model.Response
import com.survey.model.ResponseItem
import com.survey.model.Survey
import com.survey.model.SurveyItem
import com.survey.model.SurveyOption
import com.survey.repository.ResponseItemRepository
import com.survey.repository.ResponseRepository
import com.survey.repository.SurveyItemRepository
import com.survey.repository.SurveyOptionRepository
import com.survey.repository.SurveyRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SurveyService(
    private val surveyItemRepository: SurveyItemRepository,
    private val surveyOptionRepository: SurveyOptionRepository,
    private val responseRepository: ResponseRepository,
    private val responseItemRepository: ResponseItemRepository,
    private val surveyRepository: SurveyRepository
) {

    fun createSurvey(title: String, description: String?): Survey {
        if(title.isBlank()) throw RequiredFieldMissingException()
        val survey = Survey(title = title, description = description)
        return surveyRepository.save(survey)
    }

    fun getAllSurveys(): List<Survey> = surveyRepository.findAll()

//    전체 문항 조회
    fun getAllSurveyItems(): List<SurveyItem> = surveyItemRepository.findAll()
    
//    문항 저장
    fun saveSurveyItem(request: SurveyItemRequest): SurveyItem {
//        필수 필드 확인
        if(request.question.isBlank()) throw RequiredFieldMissingException()

        val options = request.options ?: throw SurveyOptionsMissingException()

//        선택형 항목인데 옵션이 없는 경우
        if(request.type in listOf("single_choice", "multiple_choice") && request.options.isEmpty()) {
            throw RequiredFieldMissingException()
        }
        
        // 항목 수가 10개 초과하는 경우
        val itemCount = surveyItemRepository.findAll().count {it.survey.id == request.surveyId}
        if(itemCount >= 10) throw SurveyItemCountException()

        val survey = surveyRepository.findById(request.surveyId).orElseThrow(){SurveyNotFoundException()}

        val surveyItem = SurveyItem(
            survey = survey,
            question = request.question,
            type = request.type,
            itemOrder = request.itemOrder
        )

        val savedItem = surveyItemRepository.save(surveyItem)

        val savedOptions: MutableList<SurveyOption> = options.map { opt ->
            SurveyOption(
                surveyItem = savedItem,
                optionValue = opt.optionValue,
                optionOrder = opt.optionOrder
            )
        }.toMutableList()
        surveyOptionRepository.saveAll(savedOptions)

        return savedItem.copy(options = savedOptions)
    }

//    문항 수정
    fun updateSurveyItem(id: UUID, request: SurveyItemRequest): SurveyItem {
        val existing = surveyItemRepository.findById(id).orElseThrow { SurveyNotFoundException() }
    
//        필수 필드 확인
        if(request.question.isBlank()) throw SurveyUpdateFieldMissingException()

//        타입이 변경되었고, 기존 응답이 존재하는 경우
        if(existing.type != request.type) {
            val hasResponse = responseItemRepository.findAll().any {it.surveyItem.id == id}
            if(hasResponse) throw SurveyTypeConflictException()
        }

        val updatedItem = existing.copy(
            question = request.question,
            type = request.type,
            itemOrder = request.itemOrder
        )
        return surveyItemRepository.save(updatedItem)
    }

    // 특정 문항의 옵션 목록 조회
    fun getOptionsByItemId(itemId: UUID): List<SurveyOption> {
        val item = surveyItemRepository.findById(itemId).orElseThrow { SurveyNotFoundException() }
        return surveyOptionRepository.findAll().filter { it.surveyItem.id == item.id }
    }

//    문항 옵션 저장
//    fun saveSurveyOption(option: SurveyOptionRequest): SurveyOption {
//        if(option.optionValue.isBlank()) throw RequiredFieldMissingException()
//        throw UnsupportedOperationException("SurveyOption 단독 생성은 지원하지 않습니다. SurveyItem 생성 시 함께 전달되어야 합니다.")
//    }

//    설문 응답 저장
    fun saveResponse(request: ResponseRequest): Response {
        if(request.responseItems.isEmpty()) throw ResponseRequiredFieldMissingException()

        val survey = surveyRepository.findById(request.surveyId).orElseThrow{ ResponseSurveyNotFoundException() }

        val response = Response(
            survey = survey,
            respondent = request.respondent
        )

        val savedResponse = responseRepository.save(response)

        val responseItems = request.responseItems.map {item ->
            val surveyItem = surveyItemRepository.findById(item.surveyItemId).orElseThrow {ResponseTypeMismatchException()}

//            단일 선택형인데 선택된 옵션이 1개가 아닌 경우
            if(surveyItem.type == "single_choice" && item.optionIds.size != 1) {
                throw ResponseTypeMismatchException()
            }

//            문항에 허용된 옵션 목록 가져오기
            val allowedOptionIds = surveyOptionRepository.findAll().filter{it.surveyItem.id == surveyItem.id}.map { it.id }

//            응답이 허용된 옵션 목록에 포함되어 있는지 확인
            if(!item.optionIds.all {it in allowedOptionIds}) {
                throw ResponseOptionNotAllowedException()
            }

            val selectedOptions = surveyOptionRepository.findAllById(item.optionIds)
            ResponseItem(
                response = savedResponse,
                surveyItem = surveyItem,
                answer = item.answer,
                selectedOptions = selectedOptions
            )
        }
        responseItemRepository.saveAll(responseItems)
        return savedResponse
    }

//    모든 응답 조회
    fun getAllResponses(): List<Response> = responseRepository.findAll()

}